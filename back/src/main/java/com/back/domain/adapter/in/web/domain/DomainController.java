package com.back.domain.adapter.in.web.domain;

import com.back.domain.application.port.in.GetDomainsUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [Incoming Web Adapter] 도메인 목록 조회 요청을 처리하는 REST controller
 */
@RestController
@RequestMapping("/api/domains")
@RequiredArgsConstructor
public class DomainController {

    private final GetDomainsUseCase getDomainsUseCase;

    /**
     * 현재 서비스에서 사용할 수 있는 도메인 목록을 반환한다.
     */
    @GetMapping
    public List<DomainResponse> getAll() {
        return getDomainsUseCase.getAll()
                .stream()
                .map(DomainResponse::from)
                .toList();
    }
}
