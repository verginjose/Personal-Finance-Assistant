package com.apigateway.filter;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtValidationGatewayFilterFactory extends AbstractGatewayFilterFactory<JwtValidationGatewayFilterFactory.Config> {

    private static final Logger log = LoggerFactory.getLogger(JwtValidationGatewayFilterFactory.class);


    @Value("${auth.service.url}")
    private String authServiceUrl;
    private final Key secretKey;
    private final  long jwtExpiration;

    public JwtValidationGatewayFilterFactory(@Value("${jwt.secret}") String secret, @Value("${jwt.expiration}" )long jwtExpiration) {
        super(Config.class);
        this.jwtExpiration = jwtExpiration;
        this.secretKey = createSecretKey(secret);
    }
    private Key createSecretKey(String secret) {
        try {
            byte[] decodedKey = Base64.getDecoder().decode(secret);
            if (decodedKey.length >= 32) {
                return Keys.hmacShaKeyFor(decodedKey);
            } else {
                return createKeyFromPlainText(secret);
            }
        } catch (IllegalArgumentException e) {
            return createKeyFromPlainText(secret);
        }
    }

    private Key createKeyFromPlainText(String secret) {
        if (secret.length() < 32) {
            secret = secret + "0".repeat(32 - secret.length());
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String email, String role) {
        return Jwts.builder()
                .subject(email)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + jwtExpiration))
                .signWith(secretKey)
                .compact();
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
        try {
            Jwts.parser()
                    .verifyWith((SecretKey) secretKey)
                    .build()
                    .parseSignedClaims(token);
            return Mono.just(true);
        } catch (SignatureException e) {
            log.warn("Invalid JWT signature: {}", e.getMessage());
            return Mono.just(false);
        } catch (JwtException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
            return Mono.just(false);
        }
    }

    private Mono<Void> onError(ServerWebExchange exchange, HttpStatus httpStatus) {
        exchange.getResponse().setStatusCode(httpStatus);
        return exchange.getResponse().setComplete();
    }

    public static class Config {
    }
}