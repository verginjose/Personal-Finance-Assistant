package com.finance.query.exception;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.toList());

        return ResponseEntity.badRequest().body(Map.of(
                "error", "Validation failed",
                "details", errors,
                "timestamp", LocalDateTime.now()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad request",
                "message", ex.getMessage(),
                "timestamp", LocalDateTime.now()));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatusException(org.springframework.web.server.ResponseStatusException ex) {
        log.warn("Response status exception: {}", ex.getMessage());
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "error", ex.getReason() != null ? ex.getReason() : "Error",
                "message", ex.getMessage(),
                "timestamp", LocalDateTime.now()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericException(Exception ex) {
        log.error("Unexpected error in analytics-service", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "Internal server error",
                "message", "An unexpected server error occurred. Please try again later.",
                "timestamp", LocalDateTime.now()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String,Object>> handleEnumMismatch(MethodArgumentTypeMismatchException ex) {
        String msg = String.format("Invalid value '%s' for parameter '%s'",
                ex.getValue(), ex.getName());
        return ResponseEntity.status(400)
                .body(Map.of(
                        "error","Bad Request",
                        "message",msg,
                        "timestamp",LocalDateTime.now()
                ));
    }

    /**
     * A missing query/path parameter is a client error. Without this handler
     * it falls through to handleGenericException and is reported as a 500,
     * which makes caller mistakes look like service outages.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", "Required request parameter '" + ex.getParameterName() + "' is missing",
                "timestamp", LocalDateTime.now()));
    }

    /** A body that cannot be parsed (unknown enum value, malformed JSON) is a 400. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "Bad Request",
                "message", "Malformed request body",
                "timestamp", LocalDateTime.now()));
    }
}
