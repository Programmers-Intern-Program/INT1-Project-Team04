package com.back.domain.application.port.out;

import com.back.domain.model.domain.Domain;
import java.util.List;
import java.util.Optional;

/**
 * [Outbound Port] Domain 정보를 외부 저장소로부터 로드하기 위한 인터페이스
 */
public interface LoadDomainPort {
    Optional<Domain> loadById(Long domainId);

    List<Domain> loadAll();
}
