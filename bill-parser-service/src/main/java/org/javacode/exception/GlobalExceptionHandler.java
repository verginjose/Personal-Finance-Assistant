package org.javacode.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.net.URI;
import java.time.Instant;

/**
 * Centralised error handling for bill-parser-service.
 * Uses RFC 7807 ProblemDetail (built into Spring Boot 3) for structured responses.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 400 Bad Request ───────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    // ── 413 Payload Too Large ─────────────────────────────────────────────────

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleFileTooLarge(MaxUploadSizeExceededException ex) {
        log.warn("File upload exceeded size limit: {}", ex.getMessage());
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "File Too Large", "File exceeds the 10 MB size limit.");
    }

    // ── 502 Bad Gateway (upstream LLM / OCR failure) ──────────────────────────

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail handleUpstreamFailure(RuntimeException ex) {
        log.error("Upstream processing failure: {}", ex.getMessage(), ex);
        return problem(HttpStatus.BAD_GATEWAY, "Processing Failed",
                "Could not process the document. Please try again or use a clearer image.");
    }

    // ── 500 Internal Server Error ─────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGeneric(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred. Please contact support.");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("about:blank"));
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }
}
