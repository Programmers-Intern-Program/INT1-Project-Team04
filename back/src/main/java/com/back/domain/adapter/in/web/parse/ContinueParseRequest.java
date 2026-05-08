package com.back.domain.adapter.in.web.parse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ContinueParseRequest(
        @NotNull Long userId,
        @NotBlank String response
) {
}
