package com.example.demoidcauth.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@RestController
public class UserController {

    @GetMapping("/user")
    public Map<String, Object> user(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            return Map.of("error", "No valid JWT found in SecurityContext");
        }
        Map<String, Object> claims = new java.util.HashMap<>();
        claims.put("sub", jwt.getSubject() != null ? jwt.getSubject() : "");
        claims.put("preferred_username", jwt.getClaimAsString("preferred_username") != null ? jwt.getClaimAsString("preferred_username") : "");
        claims.put("email", jwt.getClaimAsString("email") != null ? jwt.getClaimAsString("email") : "");
        claims.put("all_claims", jwt.getClaims()); // デバッグ用: すべてのクレームを出力
        claims.put("exp", jwt.getExpiresAt() != null ? jwt.getExpiresAt().getEpochSecond() : null);
        claims.put("iat", jwt.getIssuedAt() != null ? jwt.getIssuedAt().getEpochSecond() : null);

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Tokyo"));
        claims.put("expJst", jwt.getExpiresAt() != null ? formatter.format(jwt.getExpiresAt()) : null);
        claims.put("iatJst", jwt.getIssuedAt() != null ? formatter.format(jwt.getIssuedAt()) : null);
        return claims;
    }
}
