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

import java.time.LocalDateTime;
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
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.toList());
        recordError(400);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Validation failed", errors, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        recordError(400);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Invalid Argument", List.of(ex.getMessage()), HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurityException(SecurityException ex) {
        log.warn("Forbidden: {}", ex.getMessage());
        recordError(403);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("Forbidden", List.of(ex.getMessage()), HttpStatus.FORBIDDEN.value()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> errors = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.toList());
        recordError(400);
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("Validation failed", errors, HttpStatus.BAD_REQUEST.value()));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(org.springframework.web.server.ResponseStatusException ex) {
        log.warn("Response status exception: {}", ex.getMessage());
        recordError(ex.getStatusCode().value());
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ErrorResponse(ex.getReason() != null ? ex.getReason() : "Error",
                        List.of(ex.getMessage()), ex.getStatusCode().value()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex) {
        log.error("Unexpected error", ex);
        recordError(500);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal Server Error",
                        List.of("An unexpected server error occurred"), HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    /**
     * Increments the http.errors.total Micrometer counter.
     * Tagged with the HTTP status code so Grafana/Prometheus can query:
     *   sum by (status) (http_errors_total{application="upsert-service"})
     */
    private void recordError(int status) {
        meterRegistry.counter("http.errors.total",
                "service", "upsert-service",
                "status", String.valueOf(status))
                .increment();
    }
}

