package com.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class UserHeaderFilter implements GlobalFilter, Ordered {

    private final SecretKey secretKey;
    private final com.github.benmanes.caffeine.cache.Cache<String, Claims> claimsCache;

    public UserHeaderFilter(@Value("${jwt.secret}") String secret) {
        this.secretKey = buildKey(secret);
        this.claimsCache = com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                .maximumSize(5000)
                .expireAfterWrite(java.time.Duration.ofMinutes(5))
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String token = null;
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else {
            String queryToken = exchange.getRequest().getQueryParams().getFirst("token");
            if (queryToken != null && !queryToken.isEmpty()) {
                token = queryToken;
            }
        }

        if (token != null) {
            try {
                Claims claims = claimsCache.get(token, t -> Jwts.parser()
                        .verifyWith(secretKey)
                        .build()
                        .parseSignedClaims(t)
                        .getPayload());
                String userId = claims.get("userId", String.class);
                if (userId != null) {
                    // Remove any client-supplied X-User-Id header first (prevent injection),
                    // then inject the trusted value extracted from the signed JWT.
                    ServerHttpRequest request = exchange.getRequest().mutate()
                            .headers(h -> h.remove("X-User-Id"))
                            .header("X-User-Id", userId)
                            .build();
                    return chain.filter(exchange.mutate().request(request).build());
                }
            } catch (Exception ignored) {
                // Invalid or expired token will be blocked by Spring Security Config anyway
            }
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    private SecretKey buildKey(String secret) {
        try {
            byte[] decoded = Base64.getDecoder().decode(secret);
            if (decoded.length >= 32) {
                return Keys.hmacShaKeyFor(decoded);
            }
        } catch (IllegalArgumentException ignored) {}

        if (secret.length() < 32) {
            throw new IllegalStateException("jwt.secret must be at least 32 characters");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
