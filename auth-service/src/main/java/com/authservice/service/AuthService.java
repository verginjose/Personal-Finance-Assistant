package com.authservice.service;

import com.authservice.dto.AuthDtos.*;
import com.authservice.model.User;
import com.authservice.repository.UserRepository;
import com.authservice.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Business logic for authentication and user management.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository       userRepository;
    private final PasswordEncoder      passwordEncoder;
    private final JwtService           jwtService;
    private final AuthenticationManager authenticationManager;
    private final TokenRedisService    tokenRedisService;

    @Value("${jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    // ── Login ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        User user = userRepository.findByEmail(request.email())
                .orElseThrow();   // safe: authenticate() above already verified existence

        String token = jwtService.generateToken(user);
        String refreshToken = UUID.randomUUID().toString();

        tokenRedisService.saveRefreshToken(refreshToken, user.getEmail(), user.getId().toString(), user.getRole().name(), refreshExpirationMs);
        log.info("User logged in: {} [{}]", user.getEmail(), user.getRole());

        return new LoginResponse(token, refreshToken, user.getId().toString(), user.getEmail(), user.getRole().name());
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .role(request.role())
                .build();

        User saved = userRepository.save(user);
        log.info("Registered new user: {} [{}]", saved.getEmail(), saved.getRole());

        return new RegisterResponse(saved.getId().toString(), saved.getEmail(), saved.getRole().name());
    }

    // ── Refresh Token ─────────────────────────────────────────────────────────
    //
    // IMPORTANT: This entire flow is Redis-only. We stored userId|email|role
    // in Redis at login time exactly so we NEVER need a DB call here.
    // The access token is generated purely from the Redis session data.

    public LoginResponse refresh(RefreshTokenRequest request) {
        String oldToken = request.refreshToken();

        // 1. Validate against Redis — no DB query
        String value = tokenRedisService.getRefreshTokenValue(oldToken);
        if (value == null) {
            throw new InvalidRefreshTokenException("Invalid or expired refresh token");
        }

        // 2. Parse session data stored in Redis at login time
        String[] parts = value.split("\\|");
        if (parts.length < 3) {
            throw new InvalidRefreshTokenException("Malformed refresh token session");
        }
        String userId = parts[0];
        String email  = parts[1];
        String role   = parts[2];

        // 3. Rotate: delete old token from Redis immediately (prevents replay attacks)
        tokenRedisService.deleteRefreshToken(oldToken, email);

        // 4. Generate new access token directly from Redis data — ZERO DB QUERY
        String newAccessToken = jwtService.generateToken(email, userId, role);

        // 5. Generate new refresh token and persist it in Redis
        String newRefreshToken = UUID.randomUUID().toString();
        tokenRedisService.saveRefreshToken(newRefreshToken, email, userId, role, refreshExpirationMs);

        log.info("Tokens rotated for user: {} — no DB query performed", email);

        return new LoginResponse(newAccessToken, newRefreshToken, userId, email, role);
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    public void logout(String refreshToken) {
        String value = tokenRedisService.getRefreshTokenValue(refreshToken);
        if (value != null) {
            String[] parts = value.split("\\|");
            if (parts.length >= 2) {
                String email = parts[1];
                tokenRedisService.deleteRefreshToken(refreshToken, email);
                log.info("Logged out user session: {}", email);
            }
        }
    }



    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new InvalidCurrentPasswordException("Current password does not match");
        }

        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);

        // Revoke all sessions for this user on password change
        tokenRedisService.revokeAllUserTokens(email);
        log.info("Password changed and all active sessions revoked for user: {}", email);
    }

    // ── Custom Exceptions ─────────────────────────────────────────────────────

    public static class EmailAlreadyExistsException extends RuntimeException {
        public EmailAlreadyExistsException(String email) {
            super("Email already registered: " + email);
        }
    }

    public static class InvalidCurrentPasswordException extends RuntimeException {
        public InvalidCurrentPasswordException(String message) {
            super(message);
        }
    }

    public static class InvalidRefreshTokenException extends RuntimeException {
        public InvalidRefreshTokenException(String message) {
            super(message);
        }
    }
}