package com.back.domain.adapter.in.web.member;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateMemberRequest(
        @NotBlank
        @Size(max = 100)
        String nickname
) {
}
