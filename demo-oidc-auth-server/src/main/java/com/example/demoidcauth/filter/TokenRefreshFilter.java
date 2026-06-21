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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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
                log.debug("Attempting to authorize OAuth2 client: {}", clientRegistrationId);
                OAuth2AuthorizedClient authorizedClient = this.authorizedClientManager.authorize(authorizeRequest);

                if (authorizedClient != null) {
                    String tokenValue = authorizedClient.getAccessToken().getTokenValue();
                    Long expiresAt = authorizedClient.getAccessToken().getExpiresAt() != null
                        ? authorizedClient.getAccessToken().getExpiresAt().getEpochSecond()
                        : null;
                    log.debug("Access Token obtained. ExpiresAt (epoch): {}", expiresAt);

                    Jwt jwt = this.jwtDecoder.decode(tokenValue);
                    Long jwtIat = jwt.getIssuedAt() != null ? jwt.getIssuedAt().getEpochSecond() : null;
                    Long jwtExp = jwt.getExpiresAt() != null ? jwt.getExpiresAt().getEpochSecond() : null;
                    String jwtId = jwt.getId();  // jti claim
                    long currentTime = System.currentTimeMillis() / 1000;
                    long ttl = jwtExp != null ? jwtExp - currentTime : -1;
                    ZoneId tokyo = ZoneId.of("Asia/Tokyo");
                    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(tokyo);
                    String iatJst = jwt.getIssuedAt() != null ? formatter.format(jwt.getIssuedAt()) : "null";
                    String expJst = jwt.getExpiresAt() != null ? formatter.format(jwt.getExpiresAt()) : "null";
                    String currentJst = formatter.format(Instant.ofEpochSecond(currentTime));
                    log.debug("JWT decoded. jti: {}, iat: {}, exp: {}, current: {}, TTL: {}s, iatJst: {}, expJst: {}, currentJst: {}",
                        jwtId, jwtIat, jwtExp, currentTime, ttl, iatJst, expJst, currentJst);

                    // 前回のjtiとセッションアトリビュート "last_token_jti" から比較してリフレッシュ検知
                    Object prevJtiObj = request.getSession().getAttribute("last_token_jti");
                    String prevJti = prevJtiObj instanceof String ? (String) prevJtiObj : null;
                    Object prevExpObj = request.getSession().getAttribute("last_token_expires_at");
                    Long prevExpiresAt = prevExpObj instanceof Long ? (Long) prevExpObj : null;
                    boolean prevTokenExpiredOrNearExpiry = prevExpiresAt != null && currentTime >= prevExpiresAt - 1;
                    if (prevJti != null && !prevJti.equals(jwtId) && prevTokenExpiredOrNearExpiry) {
                        log.info("*** TOKEN REFRESHED *** - Previous jti: {}, New jti: {}, previous exp: {}, current time: {}", prevJti, jwtId, prevExpiresAt, currentTime);
                    } else if (prevJti != null && !prevJti.equals(jwtId)) {
                        log.debug("Token rotated without expiry. Not treated as refresh. Previous jti: {}, New jti: {}, previous exp: {}, current time: {}", prevJti, jwtId, prevExpiresAt, currentTime);
                    }
                    // 今回のjtiとexpiresAtを次回用に保存
                    request.getSession().setAttribute("last_token_jti", jwtId);
                    if (jwtExp != null) {
                        request.getSession().setAttribute("last_token_expires_at", jwtExp);
                    }

                    // セッション認証情報をJwtAuthenticationTokenに変換し、リソースサーバー用の認証情報とする
                    JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(jwt, oauth2Auth.getAuthorities());
                    SecurityContextHolder.getContext().setAuthentication(jwtAuth);
                    log.debug("Successfully resolved and set JwtAuthenticationToken in SecurityContext");
                } else {
                    log.warn("OAuth2AuthorizedClient is null. Clearing SecurityContext.");
                    SecurityContextHolder.clearContext();
                }
            } catch (Exception e) {
                log.error("Failed to authorize/refresh OAuth2 client or decode token. Clearing SecurityContext. Exception: {}", e.getMessage(), e);
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
