package com.back.global.common;

import java.util.UUID;

/**
 * 프로젝트 전역에서 고유 식별자(UUID) 생성을 담당하는 유틸리티 클래스
 * */
public final class UuidGenerator {

    private UuidGenerator() {
    }

    public static String create() {
        return UUID.randomUUID().toString();
    }
}