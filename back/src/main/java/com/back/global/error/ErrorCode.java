package com.back.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
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
