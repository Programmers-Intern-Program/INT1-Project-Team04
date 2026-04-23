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
public class DiscordOAuthProviderClient implements OAuthProviderClient {

    private static final String AUTHORIZE_URL = "https://discord.com/oauth2/authorize";
    private static final String TOKEN_URL = "https://discord.com/api/oauth2/token";
    private static final String PROFILE_URL = "https://discord.com/api/users/@me";

    private final OAuthClientProperties properties;
    private final RestClient.Builder restClientBuilder;

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.DISCORD;
    }

    @Override
    public URI authorizationUri(String state) {
        OAuthClientProperties.Provider discord = properties.getOauth().getDiscord();
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", discord.getClientId())
                .queryParam("redirect_uri", discord.getRedirectUri())
                .queryParam("scope", "identify email")
                .queryParam("state", state)
                .build()
                .toUri();
    }

    @Override
    public OAuthUserProfile fetchProfile(String code) {
        String accessToken = exchangeToken(code);
        Map<String, Object> profile = getProfile(accessToken);
        String nickname = stringValue(profile, "global_name");
        if (nickname == null || nickname.isBlank()) {
            nickname = stringValue(profile, "username");
        }

        return new OAuthUserProfile(
                OAuthProvider.DISCORD,
                stringValue(profile, "id"),
                stringValue(profile, "email"),
                nickname,
                accessToken
        );
    }

    private String exchangeToken(String code) {
        OAuthClientProperties.Provider discord = properties.getOauth().getDiscord();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", discord.getClientId());
        form.add("client_secret", discord.getClientSecret());
        form.add("redirect_uri", discord.getRedirectUri());
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
