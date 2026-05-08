package com.back.domain.model.token;

import com.back.domain.model.user.User;
import java.time.LocalDateTime;

/**
 * 사용자 토큰 잔액 도메인
 */
public record UserToken(
        String id,
        User user,
        int balance,
        int totalGranted,
        int totalUsed,
        LocalDateTime lastUpdatedAt,
        LocalDateTime createdAt
) {
    public UserToken decreaseBalance(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("차감 금액은 0보다 커야 합니다.");
        }
        if (balance < amount) {
            throw new IllegalStateException("토큰 잔액이 부족합니다. 현재: " + balance + ", 필요: " + amount);
        }
        return new UserToken(
                id,
                user,
                balance - amount,
                totalGranted,
                totalUsed + amount,
                LocalDateTime.now(),
                createdAt
        );
    }

    public UserToken increaseBalance(int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("충전 금액은 0보다 커야 합니다.");
        }
        return new UserToken(
                id,
                user,
                balance + amount,
                totalGranted + amount,
                totalUsed,
                LocalDateTime.now(),
                createdAt
        );
    }

    public boolean hasEnoughBalance(int amount) {
        return balance >= amount;
    }
}
