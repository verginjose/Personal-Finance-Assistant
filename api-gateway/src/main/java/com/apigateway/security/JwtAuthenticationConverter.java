
package com.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Component
public class JwtAuthenticationConverter implements ServerAuthenticationConverter {

    private final SecretKey secretKey;

    public JwtAuthenticationConverter(@Value("${jwt.secret}") String secret) {
        this.secretKey = buildKey(secret);
    }

    private SecretKey buildKey(String secret) {
        try {
            byte[] decoded = Base64.getDecoder().decode(secret);
            if (decoded.length >= 32) return Keys.hmacShaKeyFor(decoded);
        } catch (IllegalArgumentException ignored) {}

        // Pad if too short
        if (secret.length() < 32) secret = secret + "0".repeat(32 - secret.length());
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        return Mono.justOrEmpty(exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .filter(header -> header.startsWith("Bearer "))
                .map(header -> header.substring(7))
                .flatMap(token -> {
                    try {
                        Claims claims = Jwts.parser()
                                .verifyWith(secretKey)
                                .build()
                                .parseSignedClaims(token)
                                .getPayload();

                        String email = claims.getSubject();
                        String role = claims.get("role", String.class);

                        // Spring Security expects "ROLE_" prefix for hasRole() checks
                        List<SimpleGrantedAuthority> authorities = List.of(
                                new SimpleGrantedAuthority("ROLE_" + role.toUpperCase())
                        );

                        return Mono.just(
                                (Authentication) new UsernamePasswordAuthenticationToken(email, token, authorities)
                        );
                    } catch (JwtException e) {
                        return Mono.empty(); // Triggers 401
                    }
                });
    }
}