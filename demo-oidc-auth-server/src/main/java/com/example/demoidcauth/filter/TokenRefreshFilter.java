package com.example.demoidcauth.filter;

import com.example.demoidcauth.security.SwitchedJwtAuthenticationToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
import java.util.Collection;
import java.util.List;

/**
 * OAuth2 アクセストークンのリフレッシュフィルター。
 *
 * <p>
 * セッションから復元した {@link OAuth2AuthenticationToken} を使って
 * アクセストークンを取得・リフレッシュし、JWT をデコードして
 * {@link JwtAuthenticationToken} として SecurityContext にセットする。
 * </p>
 *
 * <h3>スイッチユーザー対応 (Phase A)</h3>
 * <p>
 * セッション属性 {@link OidcSwitchUserFilter#SESSION_KEY_SWITCH_NAME} が存在する場合、
 * {@link SwitchedJwtAuthenticationToken} を生成して SecurityContext にセットする。
 * Approach A では JWT 自体は元ユーザー (admin) のままであることに注意。
 * </p>
 */
public class TokenRefreshFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenRefreshFilter.class);

    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final JwtDecoder jwtDecoder;
    private final Converter<Jwt, Collection<GrantedAuthority>> authoritiesConverter;

    public TokenRefreshFilter(
            OAuth2AuthorizedClientManager authorizedClientManager,
            JwtDecoder jwtDecoder,
            Converter<Jwt, Collection<GrantedAuthority>> authoritiesConverter) {
        this.authorizedClientManager = authorizedClientManager;
        this.jwtDecoder = jwtDecoder;
        this.authoritiesConverter = authoritiesConverter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof OAuth2AuthenticationToken oauth2Auth) {
            String clientRegistrationId = oauth2Auth.getAuthorizedClientRegistrationId();

            OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
                    .withClientRegistrationId(clientRegistrationId)
                    .principal(oauth2Auth)
                    .attribute(HttpServletRequest.class.getName(), request)
                    .attribute(HttpServletResponse.class.getName(), response)
                    .build();

            try {
                log.debug("Attempting to authorize OAuth2 client: {}", clientRegistrationId);
                OAuth2AuthorizedClient authorizedClient = this.authorizedClientManager.authorize(authorizeRequest);

                if (authorizedClient != null) {
                    String tokenValue = authorizedClient.getAccessToken().getTokenValue();
                    Long expiresAt = authorizedClient.getAccessToken().getExpiresAt() != null
                            ? authorizedClient.getAccessToken().getExpiresAt().getEpochSecond()
                            : null;
                    log.debug("Access Token obtained. ExpiresAt (epoch): {}", expiresAt);

                    Jwt jwt = this.jwtDecoder.decode(tokenValue);
                    logTokenDetails(jwt);

                    // Keycloak JWT から GrantedAuthority にマッピング (共通コンバーター)
                    Collection<GrantedAuthority> jwtAuthorities = this.authoritiesConverter.convert(jwt);
                    log.debug("JWT authorities: {}", jwtAuthorities);

                    // ─── スイッチユーザー対応 (Phase A) ───────────────────────────
                    String switchedUsername = (String) request.getSession()
                            .getAttribute(OidcSwitchUserFilter.SESSION_KEY_SWITCH_NAME);
                    String switchMethod = (String) request.getSession()
                            .getAttribute(OidcSwitchUserFilter.SESSION_KEY_SWITCH_METHOD);

                    if (switchedUsername != null) {
                        // スイッチ中: SwitchedJwtAuthenticationToken を生成
                        // JWT は元ユーザー (admin) のまだが、切替先ユーザー名を保持する
                        String originalUsername = jwt.getClaimAsString("preferred_username");
                        Collection<GrantedAuthority> switchedAuthorities = buildSwitchedAuthorities();

                        SwitchedJwtAuthenticationToken switched = new SwitchedJwtAuthenticationToken(
                                jwt, switchedAuthorities, switchedUsername, originalUsername,
                                switchMethod != null ? switchMethod : "session");
                        SecurityContextHolder.getContext().setAuthentication(switched);
                        log.debug("SwitchedJwtAuthenticationToken set. switched_to='{}', method='{}', original='{}'",
                                switchedUsername, switchMethod, originalUsername);

                    } else {
                        // 通常フロー: JwtAuthenticationToken に JWT authorities を設定
                        JwtAuthenticationToken jwtAuth = new JwtAuthenticationToken(jwt, jwtAuthorities);
                        SecurityContextHolder.getContext().setAuthentication(jwtAuth);
                        log.debug("JwtAuthenticationToken set. username='{}', authorities={}",
                                jwt.getClaimAsString("preferred_username"), jwtAuthorities);
                    }

                } else {
                    log.warn("OAuth2AuthorizedClient is null. Clearing SecurityContext.");
                    SecurityContextHolder.clearContext();
                }
            } catch (Exception e) {
                log.error(
                        "Failed to authorize/refresh OAuth2 client or decode token. Clearing SecurityContext. Exception: {}",
                        e.getMessage(), e);
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * スイッチユーザー中の権限セットを生成する。
     * 標準の SwitchUserFilter に合わせて ROLE_PREVIOUS_ADMINISTRATOR を付与する。
     */
    private Collection<GrantedAuthority> buildSwitchedAuthorities() {
        return List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                // 標準 SwitchUserFilter と同じ「前の管理者」ロール (スイッチ中であることの印)
                new SimpleGrantedAuthority("ROLE_PREVIOUS_ADMINISTRATOR"));
    }

    /** トークンの詳細情報をデバッグログに出力する */
    private void logTokenDetails(Jwt jwt) {
        Long jwtIat = jwt.getIssuedAt() != null ? jwt.getIssuedAt().getEpochSecond() : null;
        Long jwtExp = jwt.getExpiresAt() != null ? jwt.getExpiresAt().getEpochSecond() : null;
        String jwtId = jwt.getId(); // jti claim
        long currentTime = System.currentTimeMillis() / 1000;
        long ttl = jwtExp != null ? jwtExp - currentTime : -1;
        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(tokyo);
        String iatJst = jwt.getIssuedAt() != null ? formatter.format(jwt.getIssuedAt()) : "null";
        String expJst = jwt.getExpiresAt() != null ? formatter.format(jwt.getExpiresAt()) : "null";
        String currentJst = formatter.format(Instant.ofEpochSecond(currentTime));
        log.debug(
                "JWT decoded. jti: {}, iat: {}, exp: {}, current: {}, TTL: {}s, iatJst: {}, expJst: {}, currentJst: {}",
                jwtId, jwtIat, jwtExp, currentTime, ttl, iatJst, expJst, currentJst);

        // jti の前回との比較でリフレッシュ検知
        // (既存ロジック省略: 必要に応じて元コードから復元可能)
    }
}
