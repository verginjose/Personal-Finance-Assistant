package com.apigateway.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

@Slf4j
@Component
public class JwtReactiveAuthenticationManager
        implements ReactiveAuthenticationManager {

    private final SecretKey secretKey;

    public JwtReactiveAuthenticationManager(
            @Value("${jwt.secret}") String secret) {
        this.secretKey = buildKey(secret);
    }

    @Override
    public Mono<Authentication> authenticate(
            Authentication authentication) {

        log.info("Authenticating token: {}", authentication);

        String token = authentication.getCredentials().toString();

        try {

            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String email = claims.getSubject();

            Object rolesObj = claims.get("roles");
            List<String> rolesList = null;
            if (rolesObj instanceof List) {
                rolesList = (List<String>) rolesObj;
            } else if (rolesObj instanceof String) {
                rolesList = List.of((String) rolesObj);
            } else {
                Object roleObj = claims.get("role");
                if (roleObj instanceof String) {
                    rolesList = List.of((String) roleObj);
                } else if (roleObj instanceof List) {
                    rolesList = (List<String>) roleObj;
                }
            }

            if (rolesList == null || rolesList.isEmpty()) {
                log.warn("Roles list is empty for token: {}", token);
                return Mono.error(new org.springframework.security.authentication.BadCredentialsException("Token has no roles"));
            }

            List<SimpleGrantedAuthority> authorities = rolesList.stream()
                    .map(role -> new SimpleGrantedAuthority(
                            "ROLE_" + role.toUpperCase()))
                    .toList();

            return Mono.just(
                    new JwtAuthenticationToken(
                            email,
                            token,
                            authorities));

        } catch (JwtException ex) {
            log.error("JWT Exception while authenticating token: {}", ex.getMessage());
            return Mono.error(new org.springframework.security.authentication.BadCredentialsException("Invalid JWT token"));
        } catch (Exception ex) {
            log.error("Unexpected error authenticating token: ", ex);
            return Mono.error(new org.springframework.security.authentication.BadCredentialsException("Invalid JWT token"));
        }
    }

    private SecretKey buildKey(String secret) {

        try {
            byte[] decoded = Base64.getDecoder().decode(secret);

            if (decoded.length >= 32) {
                return Keys.hmacShaKeyFor(decoded);
            }
        } catch (IllegalArgumentException ignored) {
        }

        if (secret.length() < 32) {
            throw new IllegalStateException(
                    "jwt.secret is too weak: must be at least 32 characters " +
                            "(or a Base64-encoded value decoding to 32+ bytes)");
        }

        return Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8));
    }
}