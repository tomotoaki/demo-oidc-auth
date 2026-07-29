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
 *   <li>{@code groups} × {@code resource_access.demo-oidc-auth-server.roles} の組み合わせ
 *       → {@code ROLE_グループ名_クライアントロール名}（例: ROLE_MEDICAL_INSTITUTION_DOCTOR）</li>
 * </ul>
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

        // ② groups クレームを取得
        List<String> groups = extractStringList(jwt.getClaim("groups"));

        // ③ resource_access.demo-oidc-auth-server.roles からクライアントロールを取得
        List<String> clientRoles = List.of();
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            Object serverAccess = resourceAccess.get("demo-oidc-auth-server");
            if (serverAccess instanceof Map<?, ?> serverMap) {
                clientRoles = extractStringList(serverMap.get("roles"));
            }
        }

        // ④ groups × clientRoles の組み合わせで複合 ROLE_ を生成
        // 例: medical-institution × doctor → ROLE_MEDICAL_INSTITUTION_DOCTOR
        for (String group : groups) {
            for (String clientRole : clientRoles) {
                String roleName = formatRoleSegment(group) + "_" + formatRoleSegment(clientRole);
                authorities.add(new SimpleGrantedAuthority("ROLE_" + roleName));
            }
        }

        return authorities;
    }

    /**
     * ロール要素文字列を大文字に変換し、ハイフン等をアンダースコアに置き換える。
     * 例: "medical-institution" -> "MEDICAL_INSTITUTION"
     */
    private String formatRoleSegment(String segment) {
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
