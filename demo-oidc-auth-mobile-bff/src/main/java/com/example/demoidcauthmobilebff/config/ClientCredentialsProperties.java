package com.example.demoidcauthmobilebff.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConfigurationProperties(prefix = "app.client-credentials")
public class ClientCredentialsProperties {

    private String tokenUri;
    private Map<String, ClientInfo> clients;

    public String getTokenUri() {
        return tokenUri;
    }

    public void setTokenUri(String tokenUri) {
        this.tokenUri = tokenUri;
    }

    public Map<String, ClientInfo> getClients() {
        return clients;
    }

    public void setClients(Map<String, ClientInfo> clients) {
        this.clients = clients;
    }

    public static class ClientInfo {
        private String clientId;
        private String clientSecret;

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }
    }
}
