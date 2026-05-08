package com.back.domain.adapter.in.web.domain;

import com.back.domain.application.result.DomainResult;

public record DomainResponse(
        Long id,
        String name
) {
    public static DomainResponse from(DomainResult result) {
        return new DomainResponse(result.id(), result.name());
    }
}
