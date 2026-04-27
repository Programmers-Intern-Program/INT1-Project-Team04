package com.back.domain.adapter.in.web.parse;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ParseRequest(
        @NotNull Long userId,
        @NotBlank String input
) {
}
