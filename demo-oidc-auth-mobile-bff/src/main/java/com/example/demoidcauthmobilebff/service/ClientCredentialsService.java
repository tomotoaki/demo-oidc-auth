package com.example.demoidcauthmobilebff.service;

import com.example.demoidcauthmobilebff.config.ClientCredentialsProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Service
public class ClientCredentialsService {

    private final RestClient restClient;
    private final ClientCredentialsProperties properties;

    public ClientCredentialsService(RestClient.Builder restClientBuilder, ClientCredentialsProperties properties) {
        this.restClient = restClientBuilder.build();
        this.properties = properties;
    }

    public String fetchToken(String cn) {
        if (cn == null || properties.getClients() == null || !properties.getClients().containsKey(cn)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Unknown or missing client CN: " + cn);
        }

        ClientCredentialsProperties.ClientInfo clientInfo = properties.getClients().get(cn);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientInfo.getClientId());
        form.add("client_secret", clientInfo.getClientSecret());

        TokenResponse response = restClient.post()
            .uri(properties.getTokenUri())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new IllegalStateException("Failed to obtain client credentials token.");
        }

        return response.accessToken();
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("access_token") String accessToken,
        @com.fasterxml.jackson.annotation.JsonProperty("expires_in") Long expiresIn
    ) {
    }
}
