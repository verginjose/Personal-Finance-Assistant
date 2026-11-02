package com.apigateway.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR;

/**
 * Circuit-breaker fallbacks.
 *
 * <p>The CircuitBreaker filter swallows the throwable that tripped it and SCG's
 * {@code ErrorWebExceptionHandler} never sees it, so a fallback used to leave no
 * trace of <em>why</em> the route failed. Every fallback therefore logs the
 * cause carried in {@link org.springframework.cloud.gateway.support.ServerWebExchangeUtils#CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR}.
 * That is what distinguishes a transport-level {@code PrematureCloseException}
 * (a pooled keep-alive connection the backend had already closed) from a
 * genuine dependency failure or an open circuit breaker.
 */
@RestController
public class FallbackController {

    private static final Logger log = LoggerFactory.getLogger(FallbackController.class);

    @RequestMapping("/fallback/upsert")
    public Mono<ResponseEntity<Map<String, String>>> upsertFallback(ServerWebExchange exchange) {
        return fallback(exchange, "Upsert service is temporarily unavailable.", "command-service");
    }

    @RequestMapping("/fallback/analytics")
    public Mono<ResponseEntity<Map<String, String>>> analyticsFallback(ServerWebExchange exchange) {
        return fallback(exchange, "Analytics service is temporarily unavailable.", "query-service");
    }

    @RequestMapping("/fallback/auth")
    public Mono<ResponseEntity<Map<String, String>>> authFallback(ServerWebExchange exchange) {
        return fallback(exchange, "Authentication service is temporarily unavailable. Try again shortly.",
                "auth-service");
    }

    @RequestMapping("/fallback/bill")
    public Mono<ResponseEntity<Map<String, String>>> billFallback(ServerWebExchange exchange) {
        return fallback(exchange, "bill parser service is temporarily unavailable.", "bill-parser-service");
    }

    private Mono<ResponseEntity<Map<String, String>>> fallback(ServerWebExchange exchange, String message,
            String service) {
        ServerHttpRequest request = exchange.getRequest();
        Throwable failure = exchange.getAttribute(CIRCUITBREAKER_EXECUTION_EXCEPTION_ATTR);
        if (failure == null) {
            // No execution exception means the breaker itself refused the call
            // (open circuit), not that the dependency threw.
            log.warn("Gateway fallback {} {} -> {}: circuit open, call not permitted",
                    request.getMethod(), request.getURI().getRawPath(), service);
        } else {
            log.warn("Gateway fallback {} {} -> {}: {}: {}",
                    request.getMethod(), request.getURI().getRawPath(), service,
                    failure.getClass().getName(), failure.getMessage());
        }
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "Service Unavailable",
                        "message", message,
                        "service", service
                )));
    }
}
