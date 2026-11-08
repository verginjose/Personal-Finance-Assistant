package com.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Injects the authenticated user id as {@code X-User-Id} from the signed JWT,
 * removing any client-supplied copy first.
 *
 * <p>A {@link WebFilter}, not a {@code GlobalFilter}: gateway global filters only
 * run for requests that match a route, and this service also serves local
 * controllers ({@code /api/bill/**}, {@code /api/receipt/**},
 * {@code /api/profile/**}, {@code /api/storage/**}) that read {@code X-User-Id}.
 * As a global filter the header was never added for those endpoints, so the
 * controllers answered 400 "Required header 'X-User-Id' is not present".
 */
@Component
public class UserHeaderFilter implements WebFilter, Ordered {

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
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String token = null;
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        } else if (JwtAuthenticationConverter.isNotificationStream(exchange)) {
            // Short-lived stream ticket; see JwtAuthenticationConverter.
            String ticket = exchange.getRequest().getQueryParams().getFirst("ticket");
            if (ticket != null && !ticket.isEmpty()) token = ticket;
        }

        if (token != null) {
            try {
                Claims claims = claimsCache.get(token, t -> Jwts.parser()
                        .verifyWith(secretKey)
                        .build()
                        .parseSignedClaims(t)
                        .getPayload());
                // Re-check expiry on cache hits (see the auth manager).
                if (claims.getExpiration() == null
                        || claims.getExpiration().before(new java.util.Date())) {
                    return chain.filter(exchange);
                }
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
