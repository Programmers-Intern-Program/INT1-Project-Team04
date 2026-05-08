package com.back.domain.application.port.in;

import com.back.domain.application.result.DomainResult;
import java.util.List;

public interface GetDomainsUseCase {

    List<DomainResult> getAll();
}
