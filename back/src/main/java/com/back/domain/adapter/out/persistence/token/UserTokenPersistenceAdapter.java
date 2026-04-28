package com.back.domain.adapter.out.persistence.token;

import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.application.port.out.LoadUserTokenPort;
import com.back.domain.application.port.out.SaveUserTokenPort;
import com.back.domain.model.token.UserToken;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UserTokenPersistenceAdapter implements LoadUserTokenPort, SaveUserTokenPort {

    private final UserTokenJpaRepository userTokenRepository;
    private final UserJpaRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<UserToken> loadByUserId(Long userId) {
        return userTokenRepository.findByUserId(userId)
                .map(UserTokenJpaEntity::toDomain);
    }

    @Override
    @Transactional
    public UserToken save(UserToken userToken) {
        var userEntity = userRepository.findById(userToken.user().id())
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        var existingEntity = userTokenRepository.findByUserId(userToken.user().id());

        if (existingEntity.isPresent()) {
            var entity = existingEntity.get();
            entity.update(userToken);
            return entity.toDomain();
        } else {
            var newEntity = UserTokenJpaEntity.fromDomain(userToken, userEntity);
            var saved = userTokenRepository.save(newEntity);
            return saved.toDomain();
        }
    }
}
