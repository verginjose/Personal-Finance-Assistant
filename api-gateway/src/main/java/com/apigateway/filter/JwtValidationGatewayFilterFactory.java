package com.apigateway.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class JwtValidationGatewayFilterFactory extends AbstractGatewayFilterFactory<JwtValidationGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(JwtValidationGatewayFilterFactory.class);

    private final WebClient webClient;

    @Value("${auth.service.url}")
    private String authServiceUrl;

    public JwtValidationGatewayFilterFactory() {
        super(Config.class);
        this.webClient = WebClient.builder().build();
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("Missing or invalid Authorization header from {}", exchange.getRequest().getRemoteAddress());
                return onError(exchange, HttpStatus.UNAUTHORIZED);
            }

            String token = authHeader.substring(7);

            return validateToken(token)
                    .flatMap(isValid -> {
                        if (isValid) {
                            return chain.filter(exchange);
                        } else {
                            log.warn("Invalid token for request: {}", exchange.getRequest().getPath());
                            return onError(exchange, HttpStatus.UNAUTHORIZED);
                        }
                    })
                    .onErrorResume(throwable -> {
                        if (throwable instanceof WebClientResponseException.Unauthorized) {
                            log.warn("Token validation returned 401");
                            return onError(exchange, HttpStatus.UNAUTHORIZED);
                        }
                        log.error("Token validation error", throwable);
                        return onError(exchange, HttpStatus.INTERNAL_SERVER_ERROR);
                    });
        };
    }

    private Mono<Boolean> validateToken(String token) {
        return webClient.get()
                .uri(authServiceUrl + "/auth/validate")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .toBodilessEntity()
                .map(response -> response.getStatusCode().is2xxSuccessful())
                .onErrorReturn(false);
    }

    private Mono<Void> onError(ServerWebExchange exchange, HttpStatus httpStatus) {
        exchange.getResponse().setStatusCode(httpStatus);
        return exchange.getResponse().setComplete();
    }

    public static class Config {
    }
}