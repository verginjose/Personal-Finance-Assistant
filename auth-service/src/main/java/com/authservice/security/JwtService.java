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

@Slf4j
@Service
public class JwtService {

  private final SecretKey secretKey;
  private final long accessExpirationMs;

  public JwtService(
          @Value("${jwt.secret}") String secret,
          @Value("${jwt.access-expiration-ms:900000}") long accessExpirationMs) {
    this.secretKey = resolveKey(secret);
    this.accessExpirationMs = accessExpirationMs;
  }

  /** Stream tickets are deliberately short-lived: EventSource only needs to
   *  establish the connection, and the stream stays open after that. */
  private static final long STREAM_TICKET_MS = 120_000;

  public String generateToken(User user, long sessionVersion) {
    return buildToken(user.getEmail(), user.getId().toString(), user.getRole().name(),
            sessionVersion, accessExpirationMs);
  }

  public String generateToken(String email, String userId, String role, long sessionVersion) {
    return buildToken(email, userId, role, sessionVersion, accessExpirationMs);
  }

  /**
   * A single-purpose credential for the SSE stream. It carries the same user
   * claims (the gateway needs them to set X-User-Id) but expires in two
   * minutes and is marked `purpose=stream`.
   */
  public String generateStreamTicket(String email, String userId, String role, long sessionVersion) {
    return buildToken(email, userId, role, sessionVersion, STREAM_TICKET_MS, "stream");
  }

  private String buildToken(String email, String userId, String role, long sessionVersion, long ttlMs) {
    return buildToken(email, userId, role, sessionVersion, ttlMs, null);
  }

  private String buildToken(String email, String userId, String role, long sessionVersion,
                            long ttlMs, String purpose) {
    var builder = Jwts.builder()
            .subject(email)
            .claim("userId", userId)
            .claim("role", role)
            // Session version: bumping it invalidates every live access token
            // for that user (e.g. on password change).
            .claim("sv", sessionVersion)
            .id(UUID.randomUUID().toString())
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + ttlMs));
    if (purpose != null) builder.claim("purpose", purpose);
    return builder.signWith(secretKey).compact();
  }

  public boolean isTokenValid(String token) {
    try {
      extractAllClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException e) {
      log.warn("JWT validation failed: {}", e.getMessage());
      return false;
    }
  }

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
    return Jwts.parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
  }

  private SecretKey resolveKey(String secret) {
    try {
      byte[] decoded = Base64.getDecoder().decode(secret);
      if (decoded.length >= 32) return Keys.hmacShaKeyFor(decoded);
    } catch (IllegalArgumentException ignored) {}
    byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
    if (raw.length < 32) {
      byte[] padded = new byte[32];
      System.arraycopy(raw, 0, padded, 0, raw.length);
      return Keys.hmacShaKeyFor(padded);
    }
    return Keys.hmacShaKeyFor(raw);
  }
}
