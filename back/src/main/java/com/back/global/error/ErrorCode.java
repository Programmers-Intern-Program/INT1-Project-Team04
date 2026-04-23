package com.back.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_OAUTH_PROVIDER(HttpStatus.BAD_REQUEST, "지원하지 않는 OAuth 제공자입니다."),
    OAUTH_STATE_MISMATCH(HttpStatus.BAD_REQUEST, "OAuth 상태 값이 올바르지 않습니다."),
    OAUTH_PROVIDER_REQUEST_FAILED(HttpStatus.BAD_GATEWAY, "OAuth 제공자 요청에 실패했습니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    DOMAIN_NOT_FOUND(HttpStatus.NOT_FOUND, "도메인을 찾을 수 없습니다."),
    MCP_TOOL_NOT_FOUND(HttpStatus.NOT_FOUND, "사용 가능한 MCP 도구를 찾을 수 없습니다."),
    MCP_REQUEST_FAILED(HttpStatus.BAD_GATEWAY, "MCP 서버 요청에 실패했습니다."),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }
}
