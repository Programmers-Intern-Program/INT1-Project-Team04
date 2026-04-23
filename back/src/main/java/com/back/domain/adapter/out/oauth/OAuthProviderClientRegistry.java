package com.back.domain.adapter.out.oauth;

import com.back.domain.model.user.OAuthProvider;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OAuthProviderClientRegistry {

    private final Map<OAuthProvider, OAuthProviderClient> clients = new EnumMap<>(OAuthProvider.class);

    public OAuthProviderClientRegistry(List<OAuthProviderClient> clients) {
        clients.forEach(client -> this.clients.put(client.provider(), client));
    }

    public OAuthProviderClient get(OAuthProvider provider) {
        OAuthProviderClient client = clients.get(provider);

        if (client == null) {
            throw new ApiException(ErrorCode.INVALID_OAUTH_PROVIDER);
        }

        return client;
    }
}
