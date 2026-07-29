package com.example.demoidcauth.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * グループ×クライアントロール認可制御デモ API。
 *
 * <p>Keycloak の {@code groups} クレームと {@code resource_access.demo-oidc-auth-server.roles} を
 * 組み合わせて生成した複合ロール ({@code ROLE_グループ名_クライアントロール名}) による認可制御を示す。</p>
 *
 * <h3>アクセス可能ユーザーの対応表</h3>
 * <table>
 *   <tr><th>エンドポイント</th><th>必要ロール</th><th>対象ユーザー</th></tr>
 *   <tr><td>/api/medical/doctor</td><td>ROLE_医療機関_医師</td><td>医療機関-医師</td></tr>
 *   <tr><td>/api/medical/staff</td><td>ROLE_医療機関_職員</td><td>医療機関-職員</td></tr>
 *   <tr><td>/api/nonmedical/doctor</td><td>ROLE_非医療機関_医師</td><td>非医療機関-医師</td></tr>
 *   <tr><td>/api/nonmedical/staff</td><td>ROLE_非医療機関_職員</td><td>非医療機関-職員</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api")
public class AuthorizationDemoController {

    // ─── 医療機関エンドポイント ──────────────────────────────────────────────

    /**
     * 医療機関の医師のみアクセス可能。
     * 必要ロール: {@code ROLE_医療機関_医師}
     */
    @GetMapping("/medical/doctor")
    @PreAuthorize("hasRole('医療機関_医師')")
    public Map<String, Object> medicalDoctor(Authentication authentication) {
        return buildResponse(
            "医療機関 - 医師専用エンドポイント",
            "医療機関に所属する医師のみアクセスできます。",
            authentication
        );
    }

    /**
     * 医療機関の職員のみアクセス可能。
     * 必要ロール: {@code ROLE_医療機関_職員}
     */
    @GetMapping("/medical/staff")
    @PreAuthorize("hasRole('医療機関_職員')")
    public Map<String, Object> medicalStaff(Authentication authentication) {
        return buildResponse(
            "医療機関 - 職員専用エンドポイント",
            "医療機関に所属する職員のみアクセスできます。",
            authentication
        );
    }

    // ─── 非医療機関エンドポイント ─────────────────────────────────────────────

    /**
     * 非医療機関の医師のみアクセス可能。
     * 必要ロール: {@code ROLE_非医療機関_医師}
     */
    @GetMapping("/nonmedical/doctor")
    @PreAuthorize("hasRole('非医療機関_医師')")
    public Map<String, Object> nonmedicalDoctor(Authentication authentication) {
        return buildResponse(
            "非医療機関 - 医師専用エンドポイント",
            "非医療機関に所属する医師のみアクセスできます。",
            authentication
        );
    }

    /**
     * 非医療機関の職員のみアクセス可能。
     * 必要ロール: {@code ROLE_非医療機関_職員}
     */
    @GetMapping("/nonmedical/staff")
    @PreAuthorize("hasRole('非医療機関_職員')")
    public Map<String, Object> nonmedicalStaff(Authentication authentication) {
        return buildResponse(
            "非医療機関 - 職員専用エンドポイント",
            "非医療機関に所属する職員のみアクセスできます。",
            authentication
        );
    }

    // ─── ユーティリティ ──────────────────────────────────────────────────────

    private Map<String, Object> buildResponse(String title, String description, Authentication authentication) {
        String grantedRoles = authentication.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .filter(a -> a.startsWith("ROLE_"))
            .collect(Collectors.joining(", "));

        return Map.of(
            "success", true,
            "title", title,
            "description", description,
            "username", authentication.getName(),
            "grantedRoles", grantedRoles
        );
    }
}
