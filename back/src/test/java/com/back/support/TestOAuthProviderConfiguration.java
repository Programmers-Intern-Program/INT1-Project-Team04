package com.back.support;

import com.back.domain.adapter.out.oauth.OAuthProviderClient;
import com.back.domain.adapter.out.oauth.OAuthProviderClientRegistry;
import com.back.domain.model.user.OAuthProvider;
import com.back.domain.model.user.OAuthUserProfile;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class TestOAuthProviderConfiguration {

    @Bean
    @Primary
    OAuthProviderClientRegistry testOAuthProviderClientRegistry() {
        return new OAuthProviderClientRegistry(List.of(
                new FakeOAuthProviderClient(OAuthProvider.KAKAO),
                new FakeOAuthProviderClient(OAuthProvider.GOOGLE),
                new FakeOAuthProviderClient(OAuthProvider.DISCORD)
        ));
    }

    private record FakeOAuthProviderClient(OAuthProvider provider) implements OAuthProviderClient {

        @Override
        public URI authorizationUri(String state) {
            return URI.create("https://provider.test/" + providerPath() + "?state=" + state);
        }

        @Override
        public OAuthUserProfile fetchProfile(String code) {
            return new OAuthUserProfile(
                    provider,
                    providerPath() + "-web-1",
                    providerPath() + "-web@example.com",
                    "웹사용자",
                    "provider-token"
            );
        }

        private String providerPath() {
            return provider.name().toLowerCase(Locale.ROOT);
        }
    }
}
