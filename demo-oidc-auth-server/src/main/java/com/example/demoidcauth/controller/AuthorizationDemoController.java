package com.example.demoidcauth.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 複数 ROLE 組み合わせ認可デモ API。
 *
 * <p>Keycloak の {@code groups} クレーム (フルパス) から生成した ROLE_GROUP_ / ROLE_ORG_ と、
 * {@code resource_access.demo-oidc-auth-server.roles} から生成した ROLE_CLIENT_ を
 * AND 条件で組み合わせることで、細粒度の認可制御を実現するサンプル。</p>
 *
 * <h3>付与される ROLE の種類</h3>
 * <table>
 *   <tr><th>プレフィックス</th><th>意味</th><th>例</th></tr>
 *   <tr><td>ROLE_GROUP_</td><td>グループ種別（path 第1セグメント）</td><td>ROLE_GROUP_MEDICAL_INSTITUTION</td></tr>
 *   <tr><td>ROLE_ORG_</td><td>機関コード（path 第2セグメント）</td><td>ROLE_ORG_1310000001</td></tr>
 *   <tr><td>ROLE_CLIENT_</td><td>職種ロール（クライアントロール）</td><td>ROLE_CLIENT_DOCTOR</td></tr>
 * </table>
 *
 * <h3>エンドポイント</h3>
 * <pre>GET /api/auth/check?group=&lt;GROUP&gt;&amp;org=&lt;ORG&gt;&amp;role=&lt;ROLE&gt;</pre>
 * <ul>
 *   <li>{@code group}: 機関種別（例: medical-institution）。省略時はチェックしない。</li>
 *   <li>{@code org}: 機関コード（例: 1310000001）。省略時はチェックしない。</li>
 *   <li>{@code role}: 職種ロール（例: doctor）。省略時はチェックしない。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthorizationDemoController {

    /**
     * 動的認可チェックエンドポイント。
     *
     * <p>指定されたパラメータに対応する ROLE を認証済みユーザーが保持しているか検証し、
     * 全条件を満たす場合のみ 200 OK を返す。不足する場合は 403 Forbidden を返す。</p>
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> check(
            @RequestParam(required = false) String group,
            @RequestParam(required = false) String org,
            @RequestParam(required = false) String role,
            Authentication authentication) {

        List<String> granted = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toList());

        // 必要 ROLE リストを組み立てる（指定パラメータのみ）
        List<String> required = buildRequiredRoles(group, org, role);

        // 全 ROLE を保持しているか AND チェック
        List<String> missing = required.stream()
            .filter(r -> !granted.contains(r))
            .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", authentication.getName());
        body.put("grantedRoles", granted.stream().filter(r -> r.startsWith("ROLE_")).collect(Collectors.joining(", ")));
        body.put("requiredRoles", required);

        if (missing.isEmpty()) {
            body.put("success", true);
            body.put("message", "アクセス許可: 必要なロールをすべて保持しています。");
            return ResponseEntity.ok(body);
        } else {
            body.put("success", false);
            body.put("missingRoles", missing);
            body.put("message", "アクセス拒否: 必要なロールが不足しています。");
            return ResponseEntity.status(403).body(body);
        }
    }

    /**
     * クエリパラメータから必要 ROLE リストを組み立てる。
     *
     * <ul>
     *   <li>group が指定された場合: {@code ROLE_GROUP_<GROUP>}</li>
     *   <li>org が指定された場合: {@code ROLE_ORG_<ORG>}</li>
     *   <li>role が指定された場合: {@code ROLE_CLIENT_<ROLE>}</li>
     * </ul>
     */
    private List<String> buildRequiredRoles(String group, String org, String role) {
        List<String> roles = new java.util.ArrayList<>();
        if (group != null && !group.isBlank()) {
            roles.add("ROLE_GROUP_" + toRoleSegment(group));
        }
        if (org != null && !org.isBlank()) {
            roles.add("ROLE_ORG_" + toRoleSegment(org));
        }
        if (role != null && !role.isBlank()) {
            roles.add("ROLE_CLIENT_" + toRoleSegment(role));
        }
        return roles;
    }

    /** ハイフンをアンダースコアに置換して大文字化 */
    private String toRoleSegment(String s) {
        return s.replace("-", "_").toUpperCase();
    }
}
