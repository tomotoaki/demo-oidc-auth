package com.example.demoidcauthmobilebff.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class TokenExchangeService {

    private final OAuth2AuthorizedClientService authorizedClientService;
    private final OAuth2AuthorizedClientManager authorizedClientManager;
    private final RestClient restClient;
    private final String tokenUri;
    private final String requestedTokenType;
    private final String clientId;
    private final String clientSecret;

    public TokenExchangeService(
            OAuth2AuthorizedClientService authorizedClientService,
            OAuth2AuthorizedClientManager authorizedClientManager,
            RestClient.Builder restClientBuilder,
            @Value("${app.oauth2.token-exchange.token-uri}") String tokenUri,
            @Value("${app.oauth2.token-exchange.requested-token-type}") String requestedTokenType,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-id}") String clientId,
            @Value("${spring.security.oauth2.client.registration.keycloak.client-secret}") String clientSecret) {
        this.authorizedClientService = authorizedClientService;
        this.authorizedClientManager = authorizedClientManager;
        this.restClient = restClientBuilder.build();
        this.tokenUri = tokenUri;
        this.requestedTokenType = requestedTokenType;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String exchange(
            OAuth2AuthenticationToken authentication,
            HttpServletRequest request,
            HttpServletResponse response) {
        OAuth2AuthorizedClient authorizedClient = authorizedClientService.loadAuthorizedClient(
            authentication.getAuthorizedClientRegistrationId(),
            authentication.getName()
        );

        if (authorizedClient == null || authorizedClient.getAccessToken() == null) {
            throw new IllegalStateException("No authorized client access token found for the current session.");
        }

        OAuth2AuthorizeRequest authorizeRequest = OAuth2AuthorizeRequest
            .withClientRegistrationId(authentication.getAuthorizedClientRegistrationId())
            .principal(authentication)
            .attribute(HttpServletRequest.class.getName(), request)
            .attribute(HttpServletResponse.class.getName(), response)
            .build();

        OAuth2AuthorizedClient refreshedClient = authorizedClientManager.authorize(authorizeRequest);
        if (refreshedClient != null && refreshedClient.getAccessToken() != null) {
            authorizedClient = refreshedClient;
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("subject_token", authorizedClient.getAccessToken().getTokenValue());
        form.add("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
        form.add("requested_token_type", requestedTokenType);

        TokenExchangeResponse tokenExchangeResponse = restClient.post()
            .uri(tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenExchangeResponse.class);

        if (tokenExchangeResponse == null || tokenExchangeResponse.accessToken() == null || tokenExchangeResponse.accessToken().isBlank()) {
            throw new IllegalStateException("Token exchange response did not contain an access token.");
        }

        return tokenExchangeResponse.accessToken();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenExchangeResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
        @com.fasterxml.jackson.annotation.JsonProperty("issued_token_type") String issuedTokenType,
        @com.fasterxml.jackson.annotation.JsonProperty("token_type") String tokenType,
        @com.fasterxml.jackson.annotation.JsonProperty("expires_in") Long expiresIn,
        String scope
    ) {
    }
}
