package com.apigateway.controller;

import com.apigateway.service.MinioStorageService;
import com.apigateway.service.MinioStorageService.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/bill")
@RequiredArgsConstructor
public class BillUploadController {

    private final RabbitTemplate rabbitTemplate;
    private final MinioStorageService storage;

    @Value("${minio.bucket:receipts}")
    private String minioBucket;

    private static final Set<String> ALLOWED = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf");

    @PostMapping(value = "/process/{userId}", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> uploadBill(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable String userId,
            @RequestPart("file") FilePart filePart,
            @RequestParam(value = "targetCurrency", required = false) String targetCurrency) {

        if (!xUserId.equalsIgnoreCase(userId)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Access denied: user mismatch")));
        }
        String receiptId = UUID.randomUUID().toString();
        return storage.store(filePart, minioBucket, xUserId, ALLOWED)
                .flatMap(object -> publishOcrJob(xUserId, receiptId, object.objectName(), "false", targetCurrency))
                .map(sent -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(Map.of(
                                "message", "Receipt accepted for OCR processing",
                                "receiptId", receiptId,
                                "url", "/api/storage/" + minioBucket + "/" + sent)))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity
                        .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                        .body(Map.of("message", e.getMessage()))))
                .onErrorResume(e -> {
                    log.error("Failed to upload bill and publish event", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("message", "Internal server error")));
                });
    }

    @PostMapping(value = "/batch-process/{userId}", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> uploadBatchBill(
            @RequestHeader("X-User-Id") String xUserId,
            @PathVariable String userId,
            @RequestPart("file") FilePart filePart,
            @RequestParam(value = "targetCurrency", required = false) String targetCurrency) {

        if (!xUserId.equalsIgnoreCase(userId)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Access denied: user mismatch")));
        }
        String receiptId = UUID.randomUUID().toString();
        String prefix = xUserId + "-batch-" + UUID.randomUUID();
        return storage.store(filePart, minioBucket, prefix, ALLOWED)
                .flatMap(object -> publishOcrJob(xUserId, receiptId, object.objectName(), "true", targetCurrency))
                .map(sent -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(Map.of(
                                "message", "Batch statement accepted for processing",
                                "receiptId", receiptId,
                                "url", "/api/storage/" + minioBucket + "/" + sent)))
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(ResponseEntity
                        .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                        .body(Map.of("message", e.getMessage()))))
                .onErrorResume(e -> {
                    log.error("Failed to upload batch statement and publish event", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("message", "Internal server error")));
                });
    }

    /** RabbitTemplate is blocking, so it runs off the event loop. */
    private Mono<String> publishOcrJob(String userId, String receiptId, String objectName, String isBatch,
                                       String targetCurrency) {
        return Mono.fromCallable(() -> {
                    Map<String, String> event = new java.util.HashMap<>();
                    event.put("userId", userId);
                    event.put("receiptId", receiptId);
                    event.put("objectName", objectName);
                    event.put("bucket", minioBucket);
                    event.put("isBatch", isBatch);
                    if (targetCurrency != null && !targetCurrency.isBlank()) {
                        event.put("targetCurrency", targetCurrency.trim().toUpperCase());
                    }
                    rabbitTemplate.convertAndSend("finance-events", "ocr.job", event);
                    return objectName;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
