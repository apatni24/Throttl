package com.throttl.ratelimiter.api;

import com.throttl.ratelimiter.api.dto.ErrorResponse;
import com.throttl.ratelimiter.domain.PolicyValidationException;
import com.throttl.ratelimiter.store.PolicyNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(PolicyValidationException.class)
    public ResponseEntity<ErrorResponse> validation(PolicyValidationException ex) {
        return ResponseEntity.badRequest().body(ErrorResponse.of("validation_error", ex.getMessage()));
    }

    @ExceptionHandler(PolicyNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(PolicyNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("not_found", ex.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException ex) {
        String message = "Malformed request";
        Throwable cause = ex.getMostSpecificCause();
        String detail = cause.getMessage() == null ? "" : cause.getMessage();
        if (detail.contains("Scope")) {
            message = "Invalid scope";
        } else if (detail.contains("Algorithm")) {
            message = "Invalid algorithm";
        } else if (detail.contains("RefillPeriod")) {
            message = "Invalid refill period";
        } else if (detail.contains("FailureMode")) {
            message = "Invalid failure mode";
        }
        return ResponseEntity.badRequest().body(ErrorResponse.of("validation_error", message));
    }
}
