package com.authservice.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtUtil {

  private final Key secretKey;
  private final long jwtExpiration;

  public JwtUtil(@Value("${jwt.secret}") String secret,
                 @Value("${jwt.expiration}") long jwtExpiration) {
    this.secretKey = createSecretKey(secret);
    this.jwtExpiration = jwtExpiration;
  }

  private Key createSecretKey(String secret) {
    try {
      // Try to decode as Base64 first
      byte[] decodedKey = Base64.getDecoder().decode(secret);
      // Check if decoded key is long enough (minimum 32 bytes for HS256)
      if (decodedKey.length >= 32) {
        return Keys.hmacShaKeyFor(decodedKey);
      } else {
        // If too short, treat as plain text
        return createKeyFromPlainText(secret);
      }
    } catch (IllegalArgumentException e) {
      // If Base64 decoding fails, treat as plain text
      return createKeyFromPlainText(secret);
    }
  }

  private Key createKeyFromPlainText(String secret) {
    // Ensure minimum length for HS256 (32 bytes)
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

  public boolean validateToken(String token) {
    try {
      Jwts.parser()
              .verifyWith((SecretKey) secretKey)
              .build()
              .parseSignedClaims(token);
      return true;
    } catch (SignatureException e) {
      System.err.println("Invalid JWT signature: " + e.getMessage());
      return false;
    } catch (JwtException e) {
      System.err.println("Invalid JWT: " + e.getMessage());
      return false;
    }
  }

  public String getEmailFromToken(String token) {
    Claims claims = Jwts.parser()
            .verifyWith((SecretKey) secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    return claims.getSubject();
  }

  public String getRoleFromToken(String token) {
    Claims claims = Jwts.parser()
            .verifyWith((SecretKey) secretKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    return claims.get("role", String.class);
  }
}