package com.fordring.common;

public record ApiResponse<T>(boolean success, T data, ApiError error, String requestId) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, RequestIds.next());
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, null, new ApiError(code, message), RequestIds.next());
    }
}

