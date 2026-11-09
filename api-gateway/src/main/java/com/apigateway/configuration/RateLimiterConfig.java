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

    /**
     * The client address as seen by our edge, never a value the client chose.
     *
     * nginx overwrites X-Real-IP with its observed peer and strips any
     * client-supplied CF-Connecting-IP. The gateway must remain reachable
     * only through this trusted proxy in production.
     */
    private String extractIp(
            org.springframework.http.server.reactive.ServerHttpRequest request) {
        String real = request.getHeaders().getFirst("X-Real-IP");
        if (looksLikeIp(real)) return real.trim();

        return Optional.ofNullable(request.getRemoteAddress())
                .map(addr -> addr.getAddress().getHostAddress())
                .orElse("unknown");
    }

    /** Allow IPv4 or IPv6 literals only; never a header value with spaces or commas. */
    private static boolean looksLikeIp(String value) {
        if (value == null) return false;
        String v = value.trim();
        if (v.isEmpty() || v.length() > 45) return false;
        return v.matches("^[0-9a-fA-F:.]+$");
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
