package com.ticketing.payment.exception;

import com.ticketing.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handlePaymentNotFound(PaymentNotFoundException ex) {
        log.warn("[ExceptionHandler] PaymentNotFound: {}", ex.getMessage());
        return ApiResponse.error(ex.getMessage(), null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[ExceptionHandler] IllegalArgument: {}", ex.getMessage());
        return ApiResponse.error(ex.getMessage(), null);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneric(Exception ex) {
        log.error("[ExceptionHandler] Unexpected error: {}", ex.getMessage(), ex);
        return ApiResponse.error("Internal server error", null);
    }
}
