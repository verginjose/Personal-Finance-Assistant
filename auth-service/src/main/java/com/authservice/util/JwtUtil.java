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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
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

  public boolean validateToken(String token) {
    try {
      Jwts.parser()
              .verifyWith((SecretKey) secretKey)
              .build()
              .parseSignedClaims(token);
      return true;
    } catch (SignatureException e) {
      log.warn("Invalid JWT signature: {}", e.getMessage());
      return false;
    } catch (JwtException e) {
      log.warn("Invalid JWT: {}", e.getMessage());
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