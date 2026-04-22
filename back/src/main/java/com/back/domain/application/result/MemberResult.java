package com.back.domain.application.result;

import com.back.domain.model.user.OAuthProvider;
import java.util.List;

public record MemberResult(
        Long id,
        String email,
        String nickname,
        List<OAuthProvider> providers
) {
}
