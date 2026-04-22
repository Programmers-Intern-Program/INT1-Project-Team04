package com.back.domain.adapter.out.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class OAuthClientProperties {

    private final Frontend frontend = new Frontend();
    private final Auth auth = new Auth();
    private final OAuth oauth = new OAuth();

    public Frontend getFrontend() {
        return frontend;
    }

    public Auth getAuth() {
        return auth;
    }

    public OAuth getOauth() {
        return oauth;
    }

    public static class Frontend {
        private String baseUrl = "http://localhost:3000";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    public static class Auth {
        private String sessionCookieName = "SESSION";
        private String stateCookieName = "OAUTH_STATE";
        private long sessionDays = 14;
        private boolean secureCookies = false;

        public String getSessionCookieName() {
            return sessionCookieName;
        }

        public void setSessionCookieName(String sessionCookieName) {
            this.sessionCookieName = sessionCookieName;
        }

        public String getStateCookieName() {
            return stateCookieName;
        }

        public void setStateCookieName(String stateCookieName) {
            this.stateCookieName = stateCookieName;
        }

        public long getSessionDays() {
            return sessionDays;
        }

        public void setSessionDays(long sessionDays) {
            this.sessionDays = sessionDays;
        }

        public boolean isSecureCookies() {
            return secureCookies;
        }

        public void setSecureCookies(boolean secureCookies) {
            this.secureCookies = secureCookies;
        }
    }

    public static class OAuth {
        private final Provider kakao = new Provider();
        private final Provider google = new Provider();
        private final Provider discord = new Provider();

        public Provider getKakao() {
            return kakao;
        }

        public Provider getGoogle() {
            return google;
        }

        public Provider getDiscord() {
            return discord;
        }
    }

    public static class Provider {
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "";

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

        public String getRedirectUri() {
            return redirectUri;
        }

        public void setRedirectUri(String redirectUri) {
            this.redirectUri = redirectUri;
        }
    }
}
