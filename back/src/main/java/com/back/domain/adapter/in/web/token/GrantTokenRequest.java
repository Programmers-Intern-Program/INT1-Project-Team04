package com.back.domain.adapter.in.web.token;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record GrantTokenRequest(
        @NotNull(message = "userId는 필수입니다")
        Long userId,
        
        @NotNull(message = "amount는 필수입니다")
        @Min(value = 1, message = "amount는 1 이상이어야 합니다")
        Integer amount,
        
        @NotBlank(message = "description은 필수입니다")
        String description
) {
}
