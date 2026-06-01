package com.apigateway.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
public class FallbackController {

    @GetMapping("/fallback/upsert")
    public Mono<ResponseEntity<Map<String, String>>> upsertFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "Service Unavailable",
                        "message", "Upsert service is temporarily unavailable.",
                        "service", "upsert-service"
                )));
    }

    @GetMapping("/fallback/analytics")
    public Mono<ResponseEntity<Map<String, String>>> analyticsFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "Service Unavailable",
                        "message", "Analytics service is temporarily unavailable.",
                        "service", "analytics-service"
                )));
    }

    @GetMapping("/fallback/bill")
    public Mono<ResponseEntity<Map<String, String>>> billFallback() {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "Service Unavailable",
                        "message", "bill parser service is temporarily unavailable.",
                        "service", "bill-parser-service"
                )));
    }
}