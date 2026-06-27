package com.example.demoidcauthmobilebff.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class ResourceServerClient {

    private final RestClient restClient;
    private final String userInfoUri;

    public ResourceServerClient(
            RestClient.Builder restClientBuilder,
            @Value("${app.resource-server.user-info-uri}") String userInfoUri) {
        this.restClient = restClientBuilder.build();
        this.userInfoUri = userInfoUri;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchUser(String accessToken) {
        return restClient.get()
            .uri(userInfoUri)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
            .retrieve()
            .body(Map.class);
    }
}
