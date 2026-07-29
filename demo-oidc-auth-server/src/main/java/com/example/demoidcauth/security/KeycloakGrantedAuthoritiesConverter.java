package com.example.demoidcauth.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Keycloak JWT から Spring Security の {@link GrantedAuthority} コレクションを抽出・変換するコンバーター。
 *
 * <h3>マッピング内容</h3>
 * <ul>
 *   <li>{@code realm_access.roles} → {@code ROLE_ロール名}（例: ROLE_ADMIN, ROLE_USER）</li>
 *   <li>{@code groups} フルパス（例: /medical-institution/1310000001/doctor）を階層分解:
 *     <ul>
 *       <li>第1セグメント → {@code ROLE_GROUP_セグメント}（例: ROLE_GROUP_MEDICAL_INSTITUTION）</li>
 *       <li>第2セグメント → {@code ROLE_ORG_セグメント}（例: ROLE_ORG_1310000001）</li>
 *     </ul>
 *   </li>
 *   <li>{@code resource_access.demo-oidc-auth-server.roles}
 *       → {@code ROLE_CLIENT_ロール名}（例: ROLE_CLIENT_DOCTOR）</li>
 * </ul>
 *
 * <p>複数 ROLE の AND 条件組み合わせで認可するため、複合 ROLE（ROLE_GROUP_ROLE）は生成しない。</p>
 */
@Component
public class KeycloakGrantedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        // ① realm_access.roles → ROLE_ロール名
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null) {
            Object rolesObj = realmAccess.get("roles");
            if (rolesObj instanceof Collection<?> roles) {
                roles.stream()
                    .filter(r -> r instanceof String)
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + (String) r))
                    .forEach(authorities::add);
            }
        }

        // ② groups クレーム（full.path=true のフルパス形式）を階層分解して ROLE_ を生成
        // 例: "/medical-institution/1310000001/doctor"
        //   → ROLE_GROUP_MEDICAL_INSTITUTION（第1セグメント: グループ種別）
        //   → ROLE_ORG_1310000001           （第2セグメント: 機関コード）
        List<String> groupPaths = extractStringList(jwt.getClaim("groups"));
        for (String path : groupPaths) {
            // 先頭の '/' を除いて分割
            String[] segments = path.replaceFirst("^/", "").split("/");
            if (segments.length >= 1 && !segments[0].isBlank()) {
                authorities.add(new SimpleGrantedAuthority(
                    "ROLE_GROUP_" + toRoleSegment(segments[0])));
            }
            if (segments.length >= 2 && !segments[1].isBlank()) {
                authorities.add(new SimpleGrantedAuthority(
                    "ROLE_ORG_" + toRoleSegment(segments[1])));
            }
            // 第3セグメント以降はクライアントロール側（ROLE_CLIENT_）で表現するため除外
        }

        // ③ resource_access.demo-oidc-auth-server.roles → ROLE_CLIENT_ロール名
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            Object serverAccess = resourceAccess.get("demo-oidc-auth-server");
            if (serverAccess instanceof Map<?, ?> serverMap) {
                List<String> clientRoles = extractStringList(serverMap.get("roles"));
                clientRoles.stream()
                    .map(r -> new SimpleGrantedAuthority("ROLE_CLIENT_" + toRoleSegment(r)))
                    .forEach(authorities::add);
            }
        }

        return authorities;
    }

    /**
     * セグメント文字列を ROLE_ 用フォーマットに変換する。
     * 例: "medical-institution" → "MEDICAL_INSTITUTION", "1310000001" → "1310000001"
     */
    private String toRoleSegment(String segment) {
        if (segment == null) {
            return "";
        }
        return segment.replace("-", "_").toUpperCase();
    }

    /**
     * Object を {@code List<String>} に変換するユーティリティ。
     * {@code Collection<?>} の各要素を String にキャストし、null を除外する。
     */
    private List<String> extractStringList(Object obj) {
        if (obj instanceof Collection<?> col) {
            return col.stream()
                .filter(e -> e instanceof String)
                .map(e -> (String) e)
                .toList();
        }
        return List.of();
    }
}
