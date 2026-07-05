package com.apigateway.auth.service;

import com.apigateway.auth.dto.AuthDtos.*;
import com.apigateway.auth.model.User;
import com.apigateway.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import com.apigateway.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository        userRepository;
    private final PasswordEncoder       passwordEncoder;
    private final JwtService            jwtService;
    private final TokenRedisService     tokenRedisService;


    @Value("${jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email()).orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("Bad credentials"));
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new org.springframework.security.authentication.BadCredentialsException("Bad credentials");
        }
        String token = jwtService.generateToken(user);
        String refreshToken = UUID.randomUUID().toString();
        tokenRedisService.saveRefreshToken(refreshToken, user.getEmail(), user.getId().toString(), user.getRole().name(), refreshExpirationMs);
        log.info("User logged in: {} [{}]", user.getEmail(), user.getRole());
        return new LoginResponse(token, refreshToken, user.getId().toString(), user.getEmail(), user.getActualUsername(), user.getRole().name());
    }

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) throw new EmailAlreadyExistsException(request.email());
        if (userRepository.existsByUsername(request.username())) throw new UsernameAlreadyExistsException(request.username());

        User user = User.builder()
                .email(request.email())
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .role(request.role())
                .build();
        User saved = userRepository.save(user);
        log.info("Registered: {} (@{})", saved.getEmail(), saved.getActualUsername());
        return new RegisterResponse(saved.getId().toString(), saved.getEmail(), saved.getActualUsername(), saved.getRole().name());
    }

    public LoginResponse refresh(RefreshTokenRequest request) {
        String value = tokenRedisService.getRefreshTokenValue(request.refreshToken());
        if (value == null) throw new InvalidRefreshTokenException("Invalid or expired refresh token");
        String[] parts = value.split("\\|");
        if (parts.length < 3) throw new InvalidRefreshTokenException("Malformed refresh token session");
        String userId = parts[0], email = parts[1], role = parts[2];
        tokenRedisService.deleteRefreshToken(request.refreshToken(), email);
        String newAccessToken = jwtService.generateToken(email, userId, role);
        String newRefreshToken = UUID.randomUUID().toString();
        tokenRedisService.saveRefreshToken(newRefreshToken, email, userId, role, refreshExpirationMs);
        log.info("Tokens rotated for user: {}", email);
        return new LoginResponse(newAccessToken, newRefreshToken, userId, email, "", role);
    }

    public void logout(String refreshToken, String authHeader) {
        String value = tokenRedisService.getRefreshTokenValue(refreshToken);
        if (value != null) {
            String[] parts = value.split("\\|");
            if (parts.length >= 2) tokenRedisService.deleteRefreshToken(refreshToken, parts[1]);
        }
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                var claims = jwtService.extractAllClaims(authHeader.substring(7));
                long ttl = claims.getExpiration().getTime() - System.currentTimeMillis();
                if (ttl > 0) tokenRedisService.blacklistAccessToken(authHeader.substring(7), ttl);
            } catch (Exception e) {
                log.warn("Failed to blacklist access token: {}", e.getMessage());
            }
        }
    }

    @Transactional
    public void changePassword(String email, ChangePasswordRequest request) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new RuntimeException("User not found"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword()))
            throw new InvalidCurrentPasswordException("Current password does not match");
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        tokenRedisService.revokeAllUserTokens(email);
        log.info("Password changed for user: {}", email);
    }

    // ── Exceptions ────────────────────────────────────────────────────────────
    public static class EmailAlreadyExistsException     extends RuntimeException { public EmailAlreadyExistsException(String e)     { super("Email already registered: " + e); } }
    public static class UsernameAlreadyExistsException  extends RuntimeException { public UsernameAlreadyExistsException(String u)   { super("Username already taken: " + u); } }
    public static class InvalidCurrentPasswordException extends RuntimeException { public InvalidCurrentPasswordException(String m)  { super(m); } }
    public static class InvalidRefreshTokenException    extends RuntimeException { public InvalidRefreshTokenException(String m)     { super(m); } }
}
