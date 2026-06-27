package com.example.demoidcauthmobilebff.controller;

import com.example.demoidcauthmobilebff.service.ResourceServerClient;
import com.example.demoidcauthmobilebff.service.TokenExchangeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class MobileBffController {

    private final TokenExchangeService tokenExchangeService;
    private final ResourceServerClient resourceServerClient;

    public MobileBffController(TokenExchangeService tokenExchangeService, ResourceServerClient resourceServerClient) {
        this.tokenExchangeService = tokenExchangeService;
        this.resourceServerClient = resourceServerClient;
    }

    @GetMapping("/")
    public Map<String, Object> index() {
        return Map.of(
            "login", "/mobile-bff/oauth2/authorization/keycloak",
            "session", "/mobile-bff/api/session",
            "userApiViaTokenExchange", "/mobile-bff/api/user"
        );
    }

    @GetMapping("/logged-out")
    public Map<String, Object> loggedOut() {
        return Map.of("status", "logged_out");
    }

    @GetMapping("/api/session")
    public Map<String, Object> session(@AuthenticationPrincipal OidcUser user) {
        return Map.of(
            "authenticated", true,
            "subject", user.getSubject(),
            "preferredUsername", valueOrEmpty(user.getPreferredUsername()),
            "email", valueOrEmpty(user.getEmail())
        );
    }

    @GetMapping("/api/user")
    public Map<String, Object> user(
            OAuth2AuthenticationToken authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        String exchangedAccessToken = tokenExchangeService.exchange(authentication, request, response);
        return Map.of(
            "bff", Map.of(
                "client", "demo-mobile-bff",
                "flow", "authorization_code + token_exchange"
            ),
            "resourceServerUser", resourceServerClient.fetchUser(exchangedAccessToken)
        );
    }

    private static String valueOrEmpty(String value) {
        return value != null ? value : "";
    }
}
