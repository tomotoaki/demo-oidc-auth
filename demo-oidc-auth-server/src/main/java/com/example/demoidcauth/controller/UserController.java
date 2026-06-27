package com.example.demoidcauth.controller;

import com.example.demoidcauth.security.SwitchedJwtAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ユーザー情報 API。
 *
 * <h3>通常状態</h3>
 * <p>JWT クレームからユーザー情報を返す。</p>
 *
 * <h3>スイッチユーザー状態 (Approach A: セッションスイッチ)</h3>
 * <p>{@link SwitchedJwtAuthenticationToken} を検知し、
 * 切替先ユーザー名で応答する。JWT 自体は元ユーザー (admin) のものであるため、
 * {@code actual_jwt_username} フィールドで元のJWT情報も開示する。</p>
 *
 * <h3>スイッチユーザー状態 (Approach B: Token Exchange)</h3>
 * <p>JWT 自体が切替先ユーザーの本物であるため、{@code sub} / {@code email} も
 * 切替先ユーザーのものになる。</p>
 */
@RestController
public class UserController {

    private static final DateTimeFormatter JST_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Tokyo"));

    @GetMapping("/user")
    public Map<String, Object> user(
            @AuthenticationPrincipal Jwt jwt,
            Authentication authentication) {

        if (jwt == null) {
            return Map.of("error", "No valid JWT found in SecurityContext");
        }

        Map<String, Object> claims = new HashMap<>();

        // ─── ロール情報 (共通) ──────────────────────────────────────────────────
        List<String> roles = extractRolesFromJwt(jwt);
        claims.put("roles", roles);

        // ─── スイッチユーザー状態の判定 ─────────────────────────────────────────
        if (authentication instanceof SwitchedJwtAuthenticationToken switched) {
            buildSwitchedUserResponse(claims, jwt, switched);
        } else {
            buildNormalUserResponse(claims, jwt);
        }

        // ─── トークン時刻情報 (共通: 実際のJWTの値) ────────────────────────────
        claims.put("exp", jwt.getExpiresAt() != null ? jwt.getExpiresAt().getEpochSecond() : null);
        claims.put("iat", jwt.getIssuedAt() != null ? jwt.getIssuedAt().getEpochSecond() : null);
        claims.put("expJst", jwt.getExpiresAt() != null ? JST_FORMATTER.format(jwt.getExpiresAt()) : null);
        claims.put("iatJst", jwt.getIssuedAt() != null ? JST_FORMATTER.format(jwt.getIssuedAt()) : null);

        // デバッグ用: すべてのJWTクレームを出力
        claims.put("all_claims", jwt.getClaims());

        return claims;
    }

    // ─── 通常ユーザーのレスポンス ──────────────────────────────────────────────

    private void buildNormalUserResponse(Map<String, Object> claims, Jwt jwt) {
        claims.put("sub", jwt.getSubject() != null ? jwt.getSubject() : "");
        claims.put("preferred_username",
            jwt.getClaimAsString("preferred_username") != null ? jwt.getClaimAsString("preferred_username") : "");
        claims.put("email",
            jwt.getClaimAsString("email") != null ? jwt.getClaimAsString("email") : "");
        claims.put("is_switched", false);
    }

    // ─── スイッチユーザー状態のレスポンス ─────────────────────────────────────

    private void buildSwitchedUserResponse(
            Map<String, Object> claims, Jwt jwt, SwitchedJwtAuthenticationToken switched) {

        boolean isExchange = "exchange".equals(switched.getSwitchMethod());

        if (isExchange) {
            // ── Approach B: Token Exchange ──────────────────────────────────────
            // JWT 自体が切替先ユーザーの本物 → クレームをそのまま返す
            claims.put("sub", jwt.getSubject() != null ? jwt.getSubject() : "");
            claims.put("preferred_username",
                jwt.getClaimAsString("preferred_username") != null
                    ? jwt.getClaimAsString("preferred_username") : "");
            claims.put("email",
                jwt.getClaimAsString("email") != null ? jwt.getClaimAsString("email") : "");
        } else {
            // ── Approach A: セッションスイッチ ──────────────────────────────────
            // JWT は元ユーザー (admin) のまま → 切替先ユーザー名で見せかける
            claims.put("sub",
                "(session switch: JWT belongs to '" + switched.getOriginalUsername() + "')");
            claims.put("preferred_username", switched.getSwitchedToUsername());
            claims.put("email", "(not available in session switch — use Token Exchange for real email)");
        }

        // スイッチ共通フィールド
        claims.put("is_switched", true);
        claims.put("switched_from", switched.getOriginalUsername());
        claims.put("switch_method", switched.getSwitchMethod());

        // 実際のJWT情報も開示 (Approach A/B の違いを学習するため)
        claims.put("actual_jwt_sub", jwt.getSubject());
        claims.put("actual_jwt_username", jwt.getClaimAsString("preferred_username"));
    }

    // ─── ユーティリティ ──────────────────────────────────────────────────────────

    /**
     * JWT の {@code realm_access.roles} から {@code ROLE_} プレフィックス付きロールリストを返す。
     */
    @SuppressWarnings("unchecked")
    private List<String> extractRolesFromJwt(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null) {
            return List.of();
        }
        Object roles = realmAccess.get("roles");
        if (roles instanceof Collection<?> roleList) {
            List<String> result = new ArrayList<>();
            for (Object r : roleList) {
                if (r instanceof String role) {
                    // Keycloak の内部ロール (offline_access, uma_authorization 等) は除外
                    if (!role.startsWith("default-roles-") && !role.equals("offline_access")
                            && !role.equals("uma_authorization")) {
                        result.add("ROLE_" + role);
                    }
                }
            }
            return result;
        }
        return List.of();
    }
}
