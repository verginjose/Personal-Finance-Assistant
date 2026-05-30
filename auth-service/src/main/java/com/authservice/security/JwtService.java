package com.authservice.security;

import com.authservice.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Responsible only for JWT creation, parsing, and validation.
 * No Spring Security coupling — pure token logic.
 */
@Slf4j
@Service
public class JwtService {

  private final SecretKey secretKey;
  private final long expirationMs;

  public JwtService(
          @Value("${jwt.secret}") String secret,
          @Value("${jwt.expiration-ms}") long expirationMs) {
    this.secretKey = resolveKey(secret);
    this.expirationMs = expirationMs;
  }

  // ── Token generation ──────────────────────────────────────────────────────

  public String generateToken(User user) {
    return Jwts.builder()
            .subject(user.getEmail())
            .claim("userId", user.getId().toString())
            .claim("role", user.getRole().name())        // stored WITHOUT "ROLE_" prefix
            .id(UUID.randomUUID().toString())             // jti: makes each token unique
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(secretKey)
            .compact();
  }

  // ── Token validation ──────────────────────────────────────────────────────

  public boolean isTokenValid(String token) {
    try {
      extractAllClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException e) {
      log.warn("JWT validation failed: {}", e.getMessage());
      return false;
    }
  }

  // ── Claims extraction ─────────────────────────────────────────────────────

  public String extractEmail(String token) {
    return extractAllClaims(token).getSubject();
  }

  public String extractRole(String token) {
    return extractAllClaims(token).get("role", String.class);
  }

  public String extractUserId(String token) {
    return extractAllClaims(token).get("userId", String.class);
  }

  public Claims extractAllClaims(String token) {
    // parseSignedClaims throws ExpiredJwtException, SignatureException, MalformedJwtException, etc.
    return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
  }

  // ── Key resolution ────────────────────────────────────────────────────────

  private SecretKey resolveKey(String secret) {
    try {
      byte[] decoded = Base64.getDecoder().decode(secret);
      if (decoded.length >= 32) return Keys.hmacShaKeyFor(decoded);
    } catch (IllegalArgumentException ignored) {
      // not base64 — treat as plaintext
    }
    byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
    if (raw.length < 32) {
      byte[] padded = new byte[32];
      System.arraycopy(raw, 0, padded, 0, raw.length);
      return Keys.hmacShaKeyFor(padded);
    }
    return Keys.hmacShaKeyFor(raw);
  }
}