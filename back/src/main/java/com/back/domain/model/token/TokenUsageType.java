package com.back.domain.model.token;

/**
 * 토큰 사용 유형
 */
public enum TokenUsageType {
    GRANT("토큰 부여"),
    USE("토큰 사용"),
    REFUND("토큰 환불"),
    ADMIN_ADJUSTMENT("관리자 조정");

    private final String description;

    TokenUsageType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
