package com.apigateway.configuration;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

@Configuration
public class RateLimiterConfig {

    private final SecretKey secretKey;

    public RateLimiterConfig(@Value("${jwt.secret}") String secret) {
        this.secretKey = buildKey(secret);
    }

    /**
     * Authenticated routes — key by userId (UUID) from JWT.
     * Falls back to IP if token is missing or unparseable.
     * UUID is stable even if user changes email.
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String authHeader = exchange.getRequest()
                    .getHeaders()
                    .getFirst(HttpHeaders.AUTHORIZATION);

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                try {
                    Claims claims = Jwts.parser()
                            .verifyWith(secretKey)
                            .build()
                            .parseSignedClaims(token)
                            .getPayload();
                    String userId = claims.get("userId", String.class);
                    if (userId != null) {
                        return Mono.just("user:" + userId);  // e.g. user:550e8400-e29b-41d4
                    }
                } catch (Exception ignored) {
                    // invalid token — fall through to IP
                }
            }

            // Fallback to IP
            return Mono.just("ip:" + extractIp(exchange.getRequest()));
        };
    }

    /**
     * Auth endpoints (/login, /register) — key by IP + path.
     * No JWT exists yet at login time.
     * Path included so login and register have independent buckets.
     */
    @Bean
    public KeyResolver ipPathKeyResolver() {
        return exchange -> {
            String ip   = extractIp(exchange.getRequest());
            String path = exchange.getRequest().getURI().getPath();
            return Mono.just("ip:" + ip + ":" + path);
            // e.g. ip:203.0.113.4:/auth/login
        };
    }

    /**
     * Global fallback — one bucket for the entire gateway.
     * Attach to any route as a second safety net.
     */
    @Bean
    public KeyResolver globalKeyResolver() {
        return exchange -> Mono.just("global");
    }

    private String extractIp(
            org.springframework.http.server.reactive.ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return Optional.ofNullable(request.getRemoteAddress())
                .map(addr -> addr.getAddress().getHostAddress())
                .orElse("unknown");
    }

    private SecretKey buildKey(String secret) {
        try {
            byte[] decoded = Base64.getDecoder().decode(secret);
            if (decoded.length >= 32) return Keys.hmacShaKeyFor(decoded);
        } catch (IllegalArgumentException ignored) {}

        if (secret.length() < 32)
            throw new IllegalStateException("jwt.secret must be at least 32 characters");

        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}   