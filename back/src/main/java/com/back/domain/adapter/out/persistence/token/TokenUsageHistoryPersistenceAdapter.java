package com.back.domain.adapter.out.persistence.token;

import com.back.domain.adapter.out.persistence.user.UserJpaRepository;
import com.back.domain.application.port.out.SaveTokenUsageHistoryPort;
import com.back.domain.model.token.TokenUsageHistory;
import com.back.global.error.ApiException;
import com.back.global.error.ErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class TokenUsageHistoryPersistenceAdapter implements SaveTokenUsageHistoryPort {

    private final TokenUsageHistoryJpaRepository historyRepository;
    private final UserJpaRepository userRepository;

    @Override
    @Transactional
    public TokenUsageHistory save(TokenUsageHistory history) {
        var userEntity = userRepository.findById(history.user().id())
                .orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND));

        var entity = TokenUsageHistoryJpaEntity.fromDomain(history, userEntity);
        var saved = historyRepository.save(entity);
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TokenUsageHistory> findByUserId(Long userId, int limit) {
        return historyRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit))
                .stream()
                .map(TokenUsageHistoryJpaEntity::toDomain)
                .toList();
    }
}
