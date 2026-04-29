package com.back.domain.adapter.out.persistence.hub;

import com.back.domain.application.port.out.LoadRecentAiDataHubPort;
import com.back.domain.application.port.out.SaveAiDataHubPort;
import com.back.domain.model.hub.AiDataHub;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Persistence Adapter] AiDataHub를 DB에 저장하는 어댑터
 */
@Component
@RequiredArgsConstructor
public class AiDataHubPersistenceAdapter implements SaveAiDataHubPort, LoadRecentAiDataHubPort {
    private final AiDataHubJpaRepository aiDataHubJpaRepository;

    @Override
    @Transactional
    public AiDataHub save(AiDataHub aiDataHub) {
        AiDataHubJpaEntity saved = aiDataHubJpaRepository.save(AiDataHubJpaEntity.from(aiDataHub));
        return saved.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AiDataHub> loadRecentByUserIdAndToolId(Long userId, Long toolId, int limit) {
        if (userId == null || toolId == null || limit <= 0) {
            return List.of();
        }
        return aiDataHubJpaRepository
                .findByUserIdAndMcpToolIdOrderByCreatedAtDescIdDesc(userId, toolId, PageRequest.of(0, limit))
                .stream()
                .map(AiDataHubJpaEntity::toDomain)
                .toList();
    }
}
