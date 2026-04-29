package com.apigateway.filter;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class RequestCorrelationGlobalFilter implements GlobalFilter, Ordered {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        final String finalCorrelationId = correlationId;

        ServerHttpRequest mutatedRequest = exchange.getRequest()
                .mutate()
                .header(CORRELATION_HEADER, finalCorrelationId)
                .build();

        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();
        long start = System.currentTimeMillis();

        return chain.filter(mutatedExchange).doFinally(signalType -> {
            long elapsedMs = System.currentTimeMillis() - start;
            log.info("correlationId={} method={} path={} status={} latencyMs={}",
                    finalCorrelationId,
                    mutatedRequest.getMethod(),
                    mutatedRequest.getPath(),
                    mutatedExchange.getResponse().getStatusCode(),
                    elapsedMs);
        });
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
