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

 * <h3>アクセス可能ユーザーの対応表</h3>
 * <table>
 *   <tr><th>エンドポイント</th><th>必要ロール</th><th>対象ユーザー</th></tr>
 *   <tr><td>/api/medical/doctor</td><td>ROLE_MEDICAL_INSTITUTION_DOCTOR</td><td>medical-institution-doctor</td></tr>
 *   <tr><td>/api/medical/staff</td><td>ROLE_MEDICAL_INSTITUTION_STAFF</td><td>medical-institution-staff</td></tr>
 *   <tr><td>/api/nonmedical/doctor</td><td>ROLE_NON_MEDICAL_INSTITUTION_DOCTOR</td><td>non-medical-institution-doctor</td></tr>
 *   <tr><td>/api/nonmedical/staff</td><td>ROLE_NON_MEDICAL_INSTITUTION_STAFF</td><td>non-medical-institution-staff</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/api")
public class AuthorizationDemoController {

    // ─── 医療機関エンドポイント ──────────────────────────────────────────────

    /**
     * 医療機関の医師のみアクセス可能。
     * 必要ロール: {@code ROLE_MEDICAL_INSTITUTION_DOCTOR}
     */
    @GetMapping("/medical/doctor")
    @PreAuthorize("hasRole('MEDICAL_INSTITUTION_DOCTOR')")
    public Map<String, Object> medicalDoctor(Authentication authentication) {
        return buildResponse(
            "Medical Institution - Doctor Endpoint",
            "Access allowed for doctors in medical-institution group.",
            authentication
        );
    }

    /**
     * 医療機関の職員のみアクセス可能。
     * 必要ロール: {@code ROLE_MEDICAL_INSTITUTION_STAFF}
     */
    @GetMapping("/medical/staff")
    @PreAuthorize("hasRole('MEDICAL_INSTITUTION_STAFF')")
    public Map<String, Object> medicalStaff(Authentication authentication) {
        return buildResponse(
            "Medical Institution - Staff Endpoint",
            "Access allowed for staff in medical-institution group.",
            authentication
        );
    }

    // ─── 非医療機関エンドポイント ─────────────────────────────────────────────

    /**
     * 非医療機関の医師のみアクセス可能。
     * 必要ロール: {@code ROLE_NON_MEDICAL_INSTITUTION_DOCTOR}
     */
    @GetMapping("/nonmedical/doctor")
    @PreAuthorize("hasRole('NON_MEDICAL_INSTITUTION_DOCTOR')")
    public Map<String, Object> nonmedicalDoctor(Authentication authentication) {
        return buildResponse(
            "Non-Medical Institution - Doctor Endpoint",
            "Access allowed for doctors in non-medical-institution group.",
            authentication
        );
    }

    /**
     * 非医療機関の職員のみアクセス可能。
     * 必要ロール: {@code ROLE_NON_MEDICAL_INSTITUTION_STAFF}
     */
    @GetMapping("/nonmedical/staff")
    @PreAuthorize("hasRole('NON_MEDICAL_INSTITUTION_STAFF')")
    public Map<String, Object> nonmedicalStaff(Authentication authentication) {
        return buildResponse(
            "Non-Medical Institution - Staff Endpoint",
            "Access allowed for staff in non-medical-institution group.",
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
