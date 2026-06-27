package com.example.demoidcauth.filter;

import com.example.demoidcauth.service.TokenExchangeService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;

/**
 * OIDC (OAuth2 Client) 対応スイッチユーザーフィルター。
 *
 * <p>Spring Security 標準の {@code SwitchUserFilter} は
 * UsernamePasswordAuthentication ベースのため、OIDC 環境では直接使用できない。
 * 本フィルターはセッション属性を使ってスイッチ状態を管理し、
 * {@link TokenRefreshFilter} と連携して仮想的なユーザー切替を実現する。</p>
 *
 * <h3>Approach A: セッションスイッチ (switch_method = "session")</h3>
 * <ul>
 *   <li>JWT は元ユーザー (admin) のまま</li>
 *   <li>セッション属性 {@value SESSION_KEY_SWITCH_NAME} に切替先ユーザー名を保存</li>
 *   <li>{@link TokenRefreshFilter} がスイッチ状態を検知して
 *       {@link com.example.demoidcauth.security.SwitchedJwtAuthenticationToken} を生成</li>
 * </ul>
 *
 * <h3>Approach B: Token Exchange (switch_method = "exchange")</h3>
 * <ul>
 *   <li>Keycloak Token Exchange で切替先の本物の JWT を取得</li>
 *   <li>セッション属性 {@value SESSION_KEY_SWITCH_NAME} に切替先ユーザー名を保存</li>
 *   <li>セッションに元の {@link OAuth2AuthorizedClient} を退避させ、
 *       {@link OAuth2AuthorizedClientRepository} のトークンを切り替え先のもので上書きする</li>
 * </ul>
 *
 * <h3>エンドポイント (SwitchUserFilter 互換)</h3>
 * <ul>
 *   <li>スイッチ: {@code POST /login/impersonate?username={target}&method={session|exchange}}</li>
 *   <li>解除:    {@code POST /logout/impersonate}</li>
 * </ul>
 */
public class OidcSwitchUserFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OidcSwitchUserFilter.class);

    public static final String SWITCH_USER_URL = "/login/impersonate";
    public static final String EXIT_SWITCH_URL = "/logout/impersonate";

    public static final String SESSION_KEY_SWITCH_NAME = "SWITCH_USER_NAME";
    public static final String SESSION_KEY_SWITCH_METHOD = "SWITCH_USER_METHOD";
    public static final String SESSION_KEY_ORIGINAL_CLIENT = "SWITCH_USER_ORIGINAL_CLIENT";

    private final String switchSuccessUrl;
    private final String exitSuccessUrl;

    private final TokenExchangeService tokenExchangeService;
    private final OAuth2AuthorizedClientRepository authorizedClientRepository;
    private final ClientRegistrationRepository clientRegistrationRepository;

    public OidcSwitchUserFilter(
            String switchSuccessUrl,
            String exitSuccessUrl,
            TokenExchangeService tokenExchangeService,
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            ClientRegistrationRepository clientRegistrationRepository) {
        this.switchSuccessUrl = switchSuccessUrl;
        this.exitSuccessUrl = exitSuccessUrl;
        this.tokenExchangeService = tokenExchangeService;
        this.authorizedClientRepository = authorizedClientRepository;
        this.clientRegistrationRepository = clientRegistrationRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String servletPath = request.getServletPath();
        String httpMethod = request.getMethod();

        if ("POST".equalsIgnoreCase(httpMethod) && SWITCH_USER_URL.equals(servletPath)) {
            attemptSwitchUser(request, response);
            return;
        }

        if ("POST".equalsIgnoreCase(httpMethod) && EXIT_SWITCH_URL.equals(servletPath)) {
            exitSwitchUser(request, response);
            return;
        }

        chain.doFilter(request, response);
    }

    // ─── スイッチ処理 ──────────────────────────────────────────────────────────

    private void attemptSwitchUser(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();

        // ① ROLE_ADMIN チェック
        if (!hasRole(currentAuth, "ROLE_ADMIN")) {
            log.warn("Switch user denied: user '{}' does not have ROLE_ADMIN",
                currentAuth != null ? currentAuth.getName() : "anonymous");
            response.sendError(HttpServletResponse.SC_FORBIDDEN,
                "Access denied: ROLE_ADMIN required to switch users");
            return;
        }

        // ② 対象ユーザー名の取得
        String targetUsername = request.getParameter("username");
        if (targetUsername == null || targetUsername.isBlank()) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST,
                "Request parameter 'username' is required");
            return;
        }

        // ③ 切替方式の取得 (デフォルト: session)
        String methodParam = request.getParameter("method");
        String switchMethod = "exchange".equalsIgnoreCase(methodParam) ? "exchange" : "session";

        HttpSession session = request.getSession(true);

        // すでにスイッチ中の場合は、一度安全に元に戻す
        String alreadySwitched = (String) session.getAttribute(SESSION_KEY_SWITCH_NAME);
        if (alreadySwitched != null) {
            log.info("Re-switching: cleaning previous switch to '{}'", alreadySwitched);
            performExitSwitch(request, response, session);
        }

        if ("exchange".equals(switchMethod)) {
            // ── Approach B: Token Exchange ──────────────────────────────────
            try {
                if (!(currentAuth instanceof OAuth2AuthenticationToken oauth2Auth)) {
                    throw new IllegalStateException("Authentication is not an OAuth2AuthenticationToken");
                }

                // 元の AuthorizedClient (adminのアクセストークン) を取得
                OAuth2AuthorizedClient originalClient = authorizedClientRepository.loadAuthorizedClient(
                    oauth2Auth.getAuthorizedClientRegistrationId(), oauth2Auth, request);

                if (originalClient == null) {
                    throw new IllegalStateException("OAuth2AuthorizedClient not found for registration ID: " +
                        oauth2Auth.getAuthorizedClientRegistrationId());
                }

                String subjectToken = originalClient.getAccessToken().getTokenValue();

                // Keycloak Token Exchange 実行
                Map<String, Object> tokenResponse = tokenExchangeService.exchangeToken(subjectToken, targetUsername);

                String newAccessTokenValue = (String) tokenResponse.get("access_token");
                String newRefreshTokenValue = (String) tokenResponse.get("refresh_token");
                Number expiresIn = (Number) tokenResponse.get("expires_in");
                Number refreshExpiresIn = (Number) tokenResponse.get("refresh_expires_in");

                Instant now = Instant.now();
                Instant expiresAt = now.plusSeconds(expiresIn != null ? expiresIn.longValue() : 60);

                // 新しいアクセストークンを構築
                OAuth2AccessToken newAccessToken = new OAuth2AccessToken(
                    OAuth2AccessToken.TokenType.BEARER,
                    newAccessTokenValue,
                    now,
                    expiresAt,
                    originalClient.getAccessToken().getScopes()
                );

                // 新しいリフレッシュトークンを構築 (もしKeycloakが返した場合)
                OAuth2RefreshToken newRefreshToken = null;
                if (newRefreshTokenValue != null) {
                    Instant refreshExpiresAt = now.plusSeconds(refreshExpiresIn != null ? refreshExpiresIn.longValue() : 1800);
                    newRefreshToken = new OAuth2RefreshToken(newRefreshTokenValue, now, refreshExpiresAt);
                }

                ClientRegistration keycloakRegistration = clientRegistrationRepository.findByRegistrationId("keycloak");
                OAuth2AuthorizedClient newClient = new OAuth2AuthorizedClient(
                    keycloakRegistration,
                    oauth2Auth.getName(), // 既存の AuthenticationPrincipal 名と合わせる
                    newAccessToken,
                    newRefreshToken
                );

                // 元のクライアント情報をセッションに退避し、新しいクライアント情報をリポジトリへ保存
                session.setAttribute(SESSION_KEY_ORIGINAL_CLIENT, originalClient);
                authorizedClientRepository.saveAuthorizedClient(newClient, oauth2Auth, request, response);

                log.info("Token Exchange Switch successful: '{}' -> '{}'", currentAuth.getName(), targetUsername);

            } catch (Exception e) {
                log.error("Token Exchange failed: {}", e.getMessage(), e);
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Token Exchange failed: " + e.getMessage());
                return;
            }
        }

        // セッションにスイッチ状態を保存
        session.setAttribute(SESSION_KEY_SWITCH_NAME, targetUsername);
        session.setAttribute(SESSION_KEY_SWITCH_METHOD, switchMethod);

        log.info("Switch user completed: '{}' -> '{}' (method: {})",
            currentAuth.getName(), targetUsername, switchMethod);

        response.sendRedirect(switchSuccessUrl);
    }

    private void exitSwitchUser(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        HttpSession session = request.getSession(false);
        if (session != null) {
            performExitSwitch(request, response, session);
        }

        response.sendRedirect(exitSuccessUrl);
    }

    /**
     * スイッチ状態を解除し、セッションおよび AuthorizedClientRepository を元の状態に復元する。
     */
    private void performExitSwitch(HttpServletRequest request, HttpServletResponse response, HttpSession session) {
        String switchedTo = (String) session.getAttribute(SESSION_KEY_SWITCH_NAME);
        String switchMethod = (String) session.getAttribute(SESSION_KEY_SWITCH_METHOD);

        if (switchedTo != null) {
            if ("exchange".equals(switchMethod)) {
                // Token Exchange 方式の解除: 退避しておいた元の Client (adminのトークン) を復元
                OAuth2AuthorizedClient originalClient = (OAuth2AuthorizedClient) session.getAttribute(SESSION_KEY_ORIGINAL_CLIENT);
                if (originalClient != null) {
                    Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
                    authorizedClientRepository.saveAuthorizedClient(originalClient, currentAuth, request, response);
                    session.removeAttribute(SESSION_KEY_ORIGINAL_CLIENT);
                    log.debug("Restored original OAuth2AuthorizedClient from session.");
                } else {
                    log.warn("Switch method was exchange, but original client was not found in session!");
                }
            }

            session.removeAttribute(SESSION_KEY_SWITCH_NAME);
            session.removeAttribute(SESSION_KEY_SWITCH_METHOD);
            log.info("Switch user exited. Returning to original user (was switched to '{}')", switchedTo);
        }
    }

    // ─── ユーティリティ ─────────────────────────────────────────────────────────

    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .anyMatch(a -> role.equals(a.getAuthority()));
    }
}
