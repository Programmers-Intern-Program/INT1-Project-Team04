package com.back.domain.application.service;

import com.back.domain.application.port.in.GetDomainsUseCase;
import com.back.domain.application.port.out.LoadDomainPort;
import com.back.domain.application.result.DomainResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * [Domain Service] 서비스에서 사용할 도메인 목록을 조회하는 Service
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DomainService implements GetDomainsUseCase {

    private final LoadDomainPort loadDomainPort;

    /**
     * 등록된 도메인 목록을 조회용 결과 모델로 변환해 반환한다.
     */
    @Override
    public List<DomainResult> getAll() {
        return loadDomainPort.loadAll()
                .stream()
                .map(domain -> new DomainResult(domain.id(), domain.name()))
                .toList();
    }
}
