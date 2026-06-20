package com.example.demoidcauth.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
        return claims;
    }
}
