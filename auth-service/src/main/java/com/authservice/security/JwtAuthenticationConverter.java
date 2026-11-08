package com.authservice.security;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthenticationConverter
        implements ServerAuthenticationConverter {

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        String token = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (token != null && token.startsWith("Bearer ")) {
            return Mono.just(new JwtAuthenticationToken(token.substring(7)));
        }
        // No query-string tokens: they leak into logs, history and Referer.
        return Mono.empty();
    }
}