package com.apigateway.controller;

import com.apigateway.service.MinioStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

@Slf4j
@RestController
@RequestMapping("/api/receipt")
@RequiredArgsConstructor
public class ReceiptUploadController {

    private final MinioStorageService storage;

    @Value("${minio.bucket:receipts}")
    private String minioBucket;

    private static final Set<String> ALLOWED = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");

    @PostMapping(value = "/upload/{userId}", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> uploadReceipt(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable String userId,
            @RequestPart("file") FilePart filePart) {

        // The path id is kept for URL compatibility but never trusted: the
        // gateway sets X-User-Id from the signed JWT and strips any client copy.
        if (!xUserId.equalsIgnoreCase(userId)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Access denied: user mismatch")));
        }
        return storage.store(filePart, minioBucket, xUserId, ALLOWED)
                .map(object -> ResponseEntity.ok(Map.of("url", object.url())))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity
                        .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                        .body(Map.of("message", e.getMessage()))))
                .onErrorResume(e -> {
                    log.error("Failed to upload receipt", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("message", "Internal server error")));
                });
    }
}
