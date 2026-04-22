package com.back.domain.adapter.out.persistence.domain;

import com.back.domain.application.port.out.LoadDomainPort;
import com.back.domain.model.domain.Domain;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * [Persistence Adapter] domain 정보 로딩을 담당하는 어댑터
 * * JPA와 내부 도메인 로직을 연결하는 브릿지 역할을 수행
 */
@Component
@RequiredArgsConstructor
public class DomainPersistenceAdapter implements LoadDomainPort {

    private final DomainJpaRepository domainJpaRepository;

    @Override
    public Optional<Domain> loadById(Long domainId) {
        return domainJpaRepository.findById(domainId)
            .map(entity -> new Domain(entity.getId(), entity.getName()));
    }
}
