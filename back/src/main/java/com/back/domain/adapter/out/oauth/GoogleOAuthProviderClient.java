package com.back.domain.adapter.out.oauth;

import com.back.domain.model.user.OAuthProvider;
import com.back.domain.model.user.OAuthUserProfile;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class GoogleOAuthProviderClient implements OAuthProviderClient {

    private static final String AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String PROFILE_URL = "https://openidconnect.googleapis.com/v1/userinfo";

    private final OAuthClientProperties properties;
    private final RestClient.Builder restClientBuilder;

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.GOOGLE;
    }

    @Override
    public URI authorizationUri(String state) {
        OAuthClientProperties.Provider google = properties.getOauth().getGoogle();
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", google.getClientId())
                .queryParam("redirect_uri", google.getRedirectUri())
                .queryParam("scope", "openid email profile")
                .queryParam("state", state)
                .build()
                .toUri();
    }

    @Override
    public OAuthUserProfile fetchProfile(String code) {
        String accessToken = exchangeToken(code);
        Map<String, Object> profile = getProfile(accessToken);
        return new OAuthUserProfile(
                OAuthProvider.GOOGLE,
                stringValue(profile, "sub"),
                stringValue(profile, "email"),
                stringValue(profile, "name"),
                accessToken
        );
    }

    private String exchangeToken(String code) {
        OAuthClientProperties.Provider google = properties.getOauth().getGoogle();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", google.getClientId());
        form.add("client_secret", google.getClientSecret());
        form.add("redirect_uri", google.getRedirectUri());
        form.add("code", code);

        Map<String, Object> token = restClientBuilder.build()
                .post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        String accessToken = token == null ? null : stringValue(token, "access_token");
        if (accessToken == null || accessToken.isBlank()) {
            throw new ApiException(ErrorCode.OAUTH_PROVIDER_REQUEST_FAILED);
        }

        return accessToken;
    }

    private Map<String, Object> getProfile(String accessToken) {
        Map<String, Object> profile = restClientBuilder.build()
                .get()
                .uri(PROFILE_URL)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (profile == null) {
            throw new ApiException(ErrorCode.OAUTH_PROVIDER_REQUEST_FAILED);
        }

        return profile;
    }

    private String stringValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : value.toString();
    }
}
