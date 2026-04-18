package com.ticketing.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final T       data;
    private final String  errorCode;
    private final String  message;
    private final String  traceId;
    private final Instant timestamp;

    private ApiResponse(boolean success, T data, String errorCode, String message, String traceId) {
        this.success   = success;
        this.data      = data;
        this.errorCode = errorCode;
        this.message   = message;
        this.traceId   = traceId;
        this.timestamp = Instant.now();
    }

    public static <T> ApiResponse<T> ok(T data, String traceId) {
        return new ApiResponse<>(true, data, null, null, traceId);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, null);
    }

    /** Used by shared GlobalExceptionHandler — carries errorCode enum name. */
    public static <T> ApiResponse<T> error(String errorCode, String message, String traceId) {
        return new ApiResponse<>(false, null, errorCode, message, traceId);
    }

    /** Convenience overload without traceId. */
    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return new ApiResponse<>(false, null, errorCode, message, null);
    }
}
