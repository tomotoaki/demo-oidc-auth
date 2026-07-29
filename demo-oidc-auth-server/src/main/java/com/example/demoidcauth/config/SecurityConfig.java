package com.example.demoidcauth.config;

import com.example.demoidcauth.filter.OidcSwitchUserFilter;
import com.example.demoidcauth.filter.TokenRefreshFilter;
import com.example.demoidcauth.service.TokenExchangeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.web.cors.CorsConfigurationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
public class SecurityConfig {

    /** Vue アプリのベース URL */
    private static final String VUE_BASE_URL = "http://localhost:5173";

    @Bean
    @Order(1)
    public SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/h2-console/**")
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers.frameOptions(frame -> frame.disable()));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CorsConfigurationSource corsConfigurationSource,
            OAuth2AuthorizedClientManager authorizedClientManager,
            JwtDecoder jwtDecoder,
            TokenExchangeService tokenExchangeService,
            OAuth2AuthorizedClientRepository authorizedClientRepository,
            ClientRegistrationRepository clientRegistrationRepository) throws Exception {

        TokenRefreshFilter tokenRefreshFilter = new TokenRefreshFilter(authorizedClientManager, jwtDecoder);

        // OidcSwitchUserFilter: SwitchUserFilter 互換の OIDC 対応版
        // POST /login/impersonate  → ユーザー切替
        // POST /logout/impersonate → ユーザー切替解除
        OidcSwitchUserFilter switchUserFilter = new OidcSwitchUserFilter(
            VUE_BASE_URL + "/",     // 切替成功後のリダイレクト先 (Vue アプリ)
            VUE_BASE_URL + "/",     // 切替解除後のリダイレクト先 (Vue アプリ)
            tokenExchangeService,
            authorizedClientRepository,
            clientRegistrationRepository
        );

        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(csrf -> csrf.disable()) // CORS経由のデモのため簡易的に無効化
            .authorizeHttpRequests(auth -> auth
                // スイッチ操作は認証済みユーザーのみ (ROLE_ADMIN チェックはフィルター内で実施)
                // ※ POST /login/impersonate を /login/** の permitAll より先に配置して上書き
                .requestMatchers(HttpMethod.POST, "/login/impersonate").authenticated()
                .requestMatchers(HttpMethod.POST, "/logout/impersonate").authenticated()
                // OAuth2 ログインフロー系 (認証不要)
                .requestMatchers("/login/**", "/oauth2/**", "/error").permitAll()
                // ユーザー情報 API (認証必須)
                .requestMatchers("/user/**").authenticated()
                // グループ×クライアントロール 認可デモ API
                // @PreAuthorize でメソッドレベル認可を使用するため、ここでは authenticated() のみ
                .requestMatchers("/api/medical/**", "/api/nonmedical/**").authenticated()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .successHandler(new SimpleUrlAuthenticationSuccessHandler(VUE_BASE_URL + "/"))
            )
            .oauth2ResourceServer(rs -> rs
                // Keycloak realm_access.roles を ROLE_ プレフィックス付きでマッピング
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            )
            .logout(logout -> logout
                .logoutSuccessUrl(VUE_BASE_URL + "/login")
                .invalidateHttpSession(true)
                .deleteCookies("SESSION")
            )
            .exceptionHandling(exceptions -> exceptions
                .defaultAuthenticationEntryPointFor(
                    jwtAuthenticationEntryPoint(),
                    request -> request.getRequestURI().startsWith("/api/v2/user")
                )
            )
            // フィルター順序:
            //   TokenRefreshFilter (トークンリフレッシュ + スイッチ状態検知)
            //     → OidcSwitchUserFilter (スイッチ操作の受付)
            //       → BearerTokenAuthenticationFilter
            .addFilterBefore(tokenRefreshFilter, BearerTokenAuthenticationFilter.class)
            .addFilterAfter(switchUserFilter, TokenRefreshFilter.class);

        return http.build();
    }

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository) {

        OAuth2AuthorizedClientProvider authorizedClientProvider =
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .authorizationCode()
                        .refreshToken()
                        .build();

        DefaultOAuth2AuthorizedClientManager authorizedClientManager =
                new DefaultOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientRepository);
        authorizedClientManager.setAuthorizedClientProvider(authorizedClientProvider);

        return authorizedClientManager;
    }

    /**
     * Keycloak JWT のロール情報を Spring Security の {@link GrantedAuthority} にマッピングする Converter。
     *
     * <h3>マッピング内容</h3>
     * <ul>
     *   <li>{@code realm_access.roles} → {@code ROLE_ロール名}（例: ROLE_ADMIN, ROLE_USER）</li>
     *   <li>{@code groups} × {@code resource_access.demo-oidc-auth-server.roles} の組み合わせ
     *       → {@code ROLE_グループ名_クライアントロール名}（例: ROLE_医療機関_医師）</li>
     * </ul>
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
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
            // 例: 医療機関 × 医師 → ROLE_医療機関_医師
            for (String group : groups) {
                for (String clientRole : clientRoles) {
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + group + "_" + clientRole));
                }
            }

            return authorities;
        });
        return converter;
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

    @Bean
    public AuthenticationEntryPoint jwtAuthenticationEntryPoint() {
        return new AuthenticationEntryPoint() {
            private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

            @Override
            public void commence(HttpServletRequest request, HttpServletResponse response,
                    AuthenticationException authException) throws IOException, ServletException {
                // トークン検証エラーの詳細をログに出力
                Throwable cause = authException.getCause();
                log.error("JWT Authentication failed. Exception type: {}, Message: {}",
                    authException.getClass().getSimpleName(), authException.getMessage());
                if (cause != null) {
                    log.error("Caused by: {} - {}", cause.getClass().getSimpleName(), cause.getMessage());
                }
                // 401 Unauthorized を返す
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("application/json");
                response.getWriter().write("{\"error\": \"Unauthorized\"}");
            }
        };
    }
}
