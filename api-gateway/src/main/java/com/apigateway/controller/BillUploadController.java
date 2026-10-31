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

    @PostMapping(value = "/process/{userId}", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> uploadBill(
            @PathVariable String userId,
            @RequestPart("file") FilePart filePart) {

        String receiptId = UUID.randomUUID().toString();
        return storage.store(filePart, minioBucket, userId, true)
                .flatMap(object -> publishOcrJob(userId, receiptId, object.objectName(), "false"))
                .map(sent -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(Map.of(
                                "message", "Receipt accepted for OCR processing",
                                "receiptId", receiptId,
                                "url", "/api/storage/" + minioBucket + "/" + sent)))
                .onErrorResume(e -> {
                    log.error("Failed to upload bill and publish event", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("message", "Internal server error")));
                });
    }

    @PostMapping(value = "/batch-process/{userId}", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<Map<String, String>>> uploadBatchBill(
            @PathVariable String userId,
            @RequestPart("file") FilePart filePart) {

        String receiptId = UUID.randomUUID().toString();
        String prefix = userId + "-batch-" + UUID.randomUUID();
        return storage.store(filePart, minioBucket, prefix, true)
                .flatMap(object -> publishOcrJob(userId, receiptId, object.objectName(), "true"))
                .map(sent -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body(Map.of(
                                "message", "Batch statement accepted for processing",
                                "receiptId", receiptId,
                                "url", "/api/storage/" + minioBucket + "/" + sent)))
                .onErrorResume(e -> {
                    log.error("Failed to upload batch statement and publish event", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(Map.of("message", "Internal server error")));
                });
    }

    /** RabbitTemplate is blocking, so it runs off the event loop. */
    private Mono<String> publishOcrJob(String userId, String receiptId, String objectName, String isBatch) {
        return Mono.fromCallable(() -> {
                    Map<String, String> event = Map.of(
                            "userId", userId,
                            "receiptId", receiptId,
                            "objectName", objectName,
                            "bucket", minioBucket,
                            "isBatch", isBatch
                    );
                    rabbitTemplate.convertAndSend("finance-events", "ocr.job", event);
                    return objectName;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }
}
