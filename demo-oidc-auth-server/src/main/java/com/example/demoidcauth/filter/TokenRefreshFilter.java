package com.example.demoidcauth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class TokenRefreshFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenRefreshFilter.class);

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final JwtDecoder jwtDecoder;

    public TokenRefreshFilter(OAuth2AuthorizedClientManager authorizedClientManager, JwtDecoder jwtDecoder) {
        this.authorizedClientManager = authorizedClientManager;
        this.jwtDecoder = jwtDecoder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof OAuth2AuthenticationToken oauth2Auth) {
            String clientRegistrationId = oauth2Auth.getAuthorizedClientRegistrationId();

            OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest.withClientRegistrationId(clientRegistrationId)
                    .principal(oauth2Auth)
                    .attribute(HttpServletRequest.class.getName(), request)
                    .attribute(HttpServletResponse.class.getName(), response)
                    .build();

            try {
                // authorizedClientManager.authorize() は、トークンが存在し期限切れなら自動的にリフレッシュを試みる
                OAuth2AuthorizedClient authorizedClient = this.authorizedClientManager.authorize(authorizeRequest);

                if (authorizedClient != null) {
                    String tokenValue = authorizedClient.getAccessToken().getTokenValue();
                    Jwt jwt = this.jwtDecoder.decode(tokenValue);
                    
                    // セッション認証情報をJwtAuthenticationTokenに変換し、リソースサーバー用の認証情報とする
                    JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(jwt, oauth2Auth.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(jwtAuth);
                    log.debug("Successfully resolved and set JwtAuthenticationToken in SecurityContext");
                } else {
                    log.warn("OAuth2AuthorizedClient is null. Clearing SecurityContext.");
                    SecurityContextHolder.clearContext();
                }
            } catch (Exception e) {
                log.error("Failed to authorize/refresh OAuth2 client or decode token. Clearing SecurityContext.", e);
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
