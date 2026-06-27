package com.example.demoidcauth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Keycloak に対する OAuth 2.0 Token Exchange (RFC 8693) を実行するサービス。
 *
 * <p>管理者ユーザーのアクセストークンを、指定した一般ユーザーのアクセストークン（およびリフレッシュトークン）
 * と交換する処理を行う。</p>
 */
@Service
public class TokenExchangeService {

    private static final Logger log = LoggerFactory.getLogger(TokenExchangeService.class);

    private final ClientRegistrationRepository clientRegistrationRepository;
    private final RestClient restClient;

    public TokenExchangeService(ClientRegistrationRepository clientRegistrationRepository) {
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.restClient = RestClient.builder().build();
    }

    /**
     * Token Exchange を実行し、取得した新しいトークン情報の Map を返す。
     *
     * @param subjectToken   現在の管理者ユーザーのアクセストークン
     * @param targetUsername スイッチ先（なりすまし先）のユーザー名
     * @return Keycloak から返されたトークンレスポンス（access_token, refresh_token, expires_in 等）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exchangeToken(String subjectToken, String targetUsername) {
        // "keycloak" のクライアント登録情報を取得
        ClientRegistration keycloakRegistration = clientRegistrationRepository.findByRegistrationId("keycloak");
        if (keycloakRegistration == null) {
            throw new IllegalStateException("ClientRegistration 'keycloak' not found");
        }

        String tokenUri = keycloakRegistration.getProviderDetails().getTokenUri();
        String clientId = keycloakRegistration.getClientId();
        String clientSecret = keycloakRegistration.getClientSecret();

        log.info("Initiating Token Exchange for target user '{}' using client '{}'", targetUsername, clientId);

        // RFC 8693 に準拠したリクエストパラメータの構築
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        formData.add("client_id", clientId);
        if (clientSecret != null) {
            formData.add("client_secret", clientSecret);
        }
        formData.add("subject_token", subjectToken);
        formData.add("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        formData.add("requested_token_type", "urn:ietf:params:oauth:token-type:access_token");
        // Keycloak 固有のパラメータ。requested_subject に対象ユーザー名を指定する。
        // ※ Keycloak の設定によっては UUID を求められる場合があるため、動作を確認する。
        formData.add("requested_subject", targetUsername);

        try {
            ResponseEntity<Map> response = restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formData)
                    .retrieve()
                    .toEntity(Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                log.info("Token Exchange successful for target user '{}'", targetUsername);
                return (Map<String, Object>) response.getBody();
            } else {
                throw new RuntimeException("Token exchange failed with status: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Error during Token Exchange for target user '{}': {}", targetUsername, e.getMessage(), e);
            throw new RuntimeException("Failed to perform token exchange: " + e.getMessage(), e);
        }
    }
}
