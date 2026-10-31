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

@Slf4j
@RestController
@RequestMapping("/api/receipt")
@RequiredArgsConstructor
public class ReceiptUploadController {

    private final MinioStorageService storage;

    @Value("${minio.bucket:receipts}")
    private String minioBucket;

    @PostMapping(value = "/upload/{userId}", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> uploadReceipt(
            @PathVariable String userId,
            @RequestPart("file") FilePart filePart) {

        return storage.store(filePart, minioBucket, userId, true)
                .map(object -> ResponseEntity.ok(Map.of("url", object.url())))
                .onErrorResume(e -> {
                    log.error("Failed to upload receipt", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("message", "Internal server error")));
                });
    }
}
