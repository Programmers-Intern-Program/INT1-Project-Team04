package com.back.domain.adapter.out.persistence.hub;

import com.back.domain.application.port.out.SaveAiDataHubPort;
import com.back.domain.model.hub.AiDataHub;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * [Persistence Adapter] AiDataHub를 DB에 저장하는 어댑터
 */
@Component
@RequiredArgsConstructor
public class AiDataHubPersistenceAdapter implements SaveAiDataHubPort {
    private final AiDataHubJpaRepository aiDataHubJpaRepository;

    @Override
    public AiDataHub save(AiDataHub aiDataHub) {
        AiDataHubJpaEntity saved = aiDataHubJpaRepository.save(AiDataHubJpaEntity.from(aiDataHub));
        return saved.toDomain();
    }
}
