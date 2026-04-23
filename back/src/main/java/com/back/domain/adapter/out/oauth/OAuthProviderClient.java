package com.back.domain.adapter.out.oauth;

import com.back.domain.model.user.OAuthProvider;
import com.back.domain.model.user.OAuthUserProfile;
import java.net.URI;

public interface OAuthProviderClient {
    OAuthProvider provider();

    URI authorizationUri(String state);

    OAuthUserProfile fetchProfile(String code);
}
