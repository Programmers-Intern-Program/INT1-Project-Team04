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
public class KakaoOAuthProviderClient implements OAuthProviderClient {

    private static final String AUTHORIZE_URL = "https://kauth.kakao.com/oauth/authorize";
    private static final String TOKEN_URL = "https://kauth.kakao.com/oauth/token";
    private static final String PROFILE_URL = "https://kapi.kakao.com/v2/user/me";

    private final OAuthClientProperties properties;
    private final RestClient.Builder restClientBuilder;

    @Override
    public OAuthProvider provider() {
        return OAuthProvider.KAKAO;
    }

    @Override
    public URI authorizationUri(String state) {
        OAuthClientProperties.Provider kakao = properties.getOauth().getKakao();
        return UriComponentsBuilder.fromUriString(AUTHORIZE_URL)
                .queryParam("response_type", "code")
                .queryParam("client_id", kakao.getClientId())
                .queryParam("redirect_uri", kakao.getRedirectUri())
                .queryParam("scope", "account_email profile_nickname")
                .queryParam("state", state)
                .build()
                .toUri();
    }

    @Override
    public OAuthUserProfile fetchProfile(String code) {
        String accessToken = exchangeToken(code);
        Map<String, Object> profile = getProfile(accessToken);
        Map<String, Object> account = mapValue(profile, "kakao_account");
        Map<String, Object> propertiesMap = mapValue(profile, "properties");

        String providerUserId = String.valueOf(profile.get("id"));
        String email = stringValue(account, "email");
        String nickname = stringValue(propertiesMap, "nickname");

        return new OAuthUserProfile(OAuthProvider.KAKAO, providerUserId, email, nickname, accessToken);
    }

    private String exchangeToken(String code) {
        OAuthClientProperties.Provider kakao = properties.getOauth().getKakao();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("client_id", kakao.getClientId());
        form.add("client_secret", kakao.getClientSecret());
        form.add("redirect_uri", kakao.getRedirectUri());
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private String stringValue(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : value.toString();
    }
}
