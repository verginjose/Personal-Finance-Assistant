package com.apigateway.exception;

import lombok.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Order(-1)
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Override
    public Mono<Void> handle(@NonNull ServerWebExchange exchange, @NonNull Throwable ex) {
        log.error("Gateway error: {} {}", exchange.getRequest().getMethod(), exchange.getRequest().getURI(), ex);

        if (ex instanceof WebClientResponseException.Unauthorized) {
            return handleUnauthorized(exchange);
        }

        if (ex instanceof org.springframework.security.access.AccessDeniedException) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            exchange.getResponse().getHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            String body = "{\"error\":\"Forbidden\",\"message\":\"Access Denied\"}";
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }

        if (ex instanceof org.springframework.security.core.AuthenticationException) {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            exchange.getResponse().getHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            String body = "{\"error\":\"Unauthorized\",\"message\":\"Invalid email or password\"}";
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }

        if (ex instanceof com.apigateway.auth.service.AuthService.EmailAlreadyExistsException ||
            ex instanceof com.apigateway.auth.service.AuthService.UsernameAlreadyExistsException ||
            ex instanceof com.apigateway.auth.service.AuthService.InvalidCurrentPasswordException ||
            ex instanceof IllegalArgumentException) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            exchange.getResponse().getHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            String body = String.format("{\"error\":\"Bad Request\",\"message\":\"%s\"}", ex.getMessage().replace("\"", "'"));
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
            return exchange.getResponse().writeWith(Mono.just(buffer));
        }

        if (ex instanceof com.apigateway.auth.service.AuthService.InvalidRefreshTokenException) {
            return handleUnauthorized(exchange);
        }

        // Handle other exceptions as needed
        exchange.getResponse().setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR);
        exchange.getResponse().getHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        String body = "{\"error\":\"Internal Server Error\",\"message\":\"An unexpected server error occurred\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Mono<Void> handleUnauthorized(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().add("Content-Type", MediaType.APPLICATION_JSON_VALUE);

        String body = "{\"error\":\"Unauthorized\",\"message\":\"Invalid or expired token\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body.getBytes());

        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}