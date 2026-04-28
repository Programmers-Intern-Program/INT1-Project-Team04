package com.back.domain.adapter.out.persistence.token;

import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.application.port.out.LoadTokenStatisticsPort;
import com.back.domain.application.port.out.LoadUserTokenPort;
import com.back.domain.application.port.out.SaveUserTokenPort;
import com.back.domain.model.token.UserToken;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UserTokenPersistenceAdapter implements LoadUserTokenPort, SaveUserTokenPort, LoadTokenStatisticsPort {

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

    // 관리자용 통계 메서드
    @Override
    @Transactional(readOnly = true)
    public List<UserToken> loadAllUserTokens(int page, int size) {
        return userTokenRepository.findAllWithUser(PageRequest.of(page, size))
                .stream()
                .map(UserTokenJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long getTotalGranted() {
        Long sum = userTokenRepository.sumTotalGranted();
        return sum != null ? sum : 0L;
    }

    @Override
    @Transactional(readOnly = true)
    public long getTotalUsed() {
        Long sum = userTokenRepository.sumTotalUsed();
        return sum != null ? sum : 0L;
    }

    @Override
    @Transactional(readOnly = true)
    public long getTotalBalance() {
        Long sum = userTokenRepository.sumBalance();
        return sum != null ? sum : 0L;
    }

    @Override
    @Transactional(readOnly = true)
    public long getActiveUserCount() {
        Long count = userTokenRepository.countActiveUsers();
        return count != null ? count : 0L;
    }

    @Override
    @Transactional(readOnly = true)
    public long getTotalUserCount() {
        return userTokenRepository.count();
    }
}
