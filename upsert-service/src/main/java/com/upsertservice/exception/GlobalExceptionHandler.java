package com.upsertservice.exception;

import com.upsertservice.dto.ErrorResponse;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MeterRegistry meterRegistry;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.toList());
        recordError(400);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Validation failed", errors, 400));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> errors = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.toList());
        recordError(400);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Validation failed", errors, 400));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("IllegalArgument: {}", ex.getMessage());
        String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
        HttpStatus status = msg.contains("not found") ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        recordError(status.value());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(
                        status == HttpStatus.NOT_FOUND ? "Not Found" : "Invalid Argument",
                        List.of(ex.getMessage()),
                        status.value()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurity(SecurityException ex) {
        log.warn("Forbidden: {}", ex.getMessage());
        recordError(403);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("Forbidden", List.of(ex.getMessage()), 403));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(org.springframework.web.server.ResponseStatusException ex) {
        log.warn("ResponseStatusException: {}", ex.getMessage());
        recordError(ex.getStatusCode().value());
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ErrorResponse(
                        ex.getReason() != null ? ex.getReason() : "Error",
                        List.of(ex.getMessage()),
                        ex.getStatusCode().value()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        recordError(500);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal Server Error",
                        List.of("An unexpected server error occurred"), 500));
    }

    private void recordError(int status) {
        meterRegistry.counter("http.errors.total",
                        "service", "upsert-service",
                        "status", String.valueOf(status))
                .increment();
    }
}