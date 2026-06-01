package com.apigateway.security;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class UserIdValidationFilter implements GlobalFilter, Ordered {

    private static final String UUID_REGEX =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String userId = exchange.getRequest().getQueryParams().getFirst("userId");

        // No userId param — let it through (endpoint may not need it)
        if (userId == null || userId.isBlank()) {
            return chain.filter(exchange);
        }

        // Valid UUID — let it through
        if (userId.matches(UUID_REGEX)) {
            return chain.filter(exchange);
        }

        // Invalid UUID format — return 400 immediately
        return badRequest(exchange,
                "{\"error\":\"Bad Request\",\"message\":\"userId must be a valid UUID\",\"status\":400}");
    }

    @Override
    public int getOrder() {
        // Run just after UserHeaderFilter (which is HIGHEST_PRECEDENCE + 5)
        return Ordered.HIGHEST_PRECEDENCE + 6;
    }

    private Mono<Void> badRequest(ServerWebExchange exchange, String body) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = exchange.getResponse()
                .bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}