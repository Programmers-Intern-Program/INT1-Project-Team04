package com.back.standard.response;

public record ApiResponse<T>(
        boolean success,
        T data
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data);
    }
}
