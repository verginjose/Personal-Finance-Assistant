package com.authservice.service;

import com.authservice.dto.AuthDtos.*;
import com.authservice.model.User;
import com.authservice.repository.UserRepository;
import com.authservice.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.UUID;
import java.util.concurrent.Callable;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository    userRepository;
    private final PasswordEncoder   passwordEncoder;
    private final JwtService        jwtService;
    private final TokenRedisService tokenRedisService;

    @Value("${jwt.refresh-expiration-ms:604800000}")
    private long refreshExpirationMs;

    /**
     * BCrypt costs ~50-150 ms of CPU. Running it on the Netty event loop would
     * block every other request assigned to that loop, so every hashing call
     * goes through here instead.
     */
    private static <T> Mono<T> offload(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    @Transactional(readOnly = true)
    public Mono<LoginResponse> login(LoginRequest request) {
        return userRepository.findByEmail(request.email())
                .switchIfEmpty(Mono.error(new BadCredentialsException("Bad credentials")))
                .flatMap(user -> offload(() -> passwordEncoder.matches(request.password(), user.getPassword()))
                        .flatMap(matches -> {
                            if (!matches) {
                                return Mono.error(new BadCredentialsException("Bad credentials"));
                            }
                            String token = jwtService.generateToken(user);
                            String refreshToken = UUID.randomUUID().toString();
                            return tokenRedisService.saveRefreshTokenReactive(
                                            refreshToken, user.getEmail(), user.getId().toString(),
                                            user.getRole().name(), refreshExpirationMs)
                                    .then(Mono.fromCallable(() -> {
                                        log.info("User logged in: {} [{}]", user.getEmail(), user.getRole());
                                        return new LoginResponse(token, refreshToken, user.getId().toString(),
                                                user.getEmail(), user.getActualUsername(), user.getRole().name());
                                    }).subscribeOn(Schedulers.boundedElastic()));
                        }));
    }

    @Transactional
    public Mono<RegisterResponse> register(RegisterRequest request) {
        return userRepository.existsByEmail(request.email())
                .flatMap(existsEmail -> {
                    if (existsEmail) return Mono.error(new EmailAlreadyExistsException(request.email()));
                    return userRepository.existsByUsername(request.username())
                            .flatMap(existsUser -> {
                                if (existsUser) return Mono.error(new UsernameAlreadyExistsException(request.username()));
                                return offload(() -> passwordEncoder.encode(request.password()))
                                        .flatMap(hash -> userRepository.save(User.builder()
                                                .email(request.email())
                                                .username(request.username())
                                                .password(hash)
                                                .role(request.role())
                                                .build()))
                                        .map(saved -> {
                                            log.info("Registered: {} (@{})", saved.getEmail(), saved.getActualUsername());
                                            return new RegisterResponse(saved.getId().toString(), saved.getEmail(),
                                                    saved.getActualUsername(), saved.getRole().name());
                                        });
                            });
                });
    }

    public Mono<LoginResponse> refresh(RefreshTokenRequest request) {
        return tokenRedisService.getRefreshTokenValueReactive(request.refreshToken())
                .switchIfEmpty(Mono.error(new InvalidRefreshTokenException("Invalid or expired refresh token")))
                .flatMap(value -> {
                    String[] parts = value.split("\\|");
                    if (parts.length < 3) return Mono.error(new InvalidRefreshTokenException("Malformed refresh token session"));
                    String userId = parts[0], email = parts[1], role = parts[2];

                    return tokenRedisService.deleteRefreshTokenReactive(request.refreshToken(), email)
                            .then(Mono.defer(() -> {
                                String newAccessToken = jwtService.generateToken(email, userId, role);
                                String newRefreshToken = UUID.randomUUID().toString();
                                return tokenRedisService.saveRefreshTokenReactive(newRefreshToken, email, userId, role, refreshExpirationMs)
                                        .thenReturn(new LoginResponse(newAccessToken, newRefreshToken, userId, email, "", role))
                                        .doOnSuccess(r -> log.info("Tokens rotated for user: {}", email));
                            }));
                });
    }

    public Mono<Void> logout(String refreshToken, String authHeader) {
        Mono<Void> deleteRefresh = tokenRedisService.getRefreshTokenValueReactive(refreshToken)
                .flatMap(value -> {
                    String[] parts = value.split("\\|");
                    if (parts.length >= 2) return tokenRedisService.deleteRefreshTokenReactive(refreshToken, parts[1]);
                    return Mono.empty();
                })
                .then();

        Mono<Void> blacklistAccess = Mono.empty();
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                var claims = jwtService.extractAllClaims(authHeader.substring(7));
                long ttl = claims.getExpiration().getTime() - System.currentTimeMillis();
                if (ttl > 0) {
                    blacklistAccess = tokenRedisService.blacklistAccessTokenReactive(authHeader.substring(7), ttl);
                }
            } catch (Exception e) {
                log.warn("Failed to blacklist access token: {}", e.getMessage());
            }
        }
        return deleteRefresh.then(blacklistAccess);
    }

    @Transactional
    public Mono<Void> changePassword(String email, ChangePasswordRequest request) {
        return userRepository.findByEmail(email)
                .switchIfEmpty(Mono.error(new RuntimeException("User not found")))
                .flatMap(user -> offload(() -> passwordEncoder.matches(request.currentPassword(), user.getPassword()))
                        .flatMap(matches -> {
                            if (!matches) {
                                return Mono.error(new InvalidCurrentPasswordException("Current password does not match"));
                            }
                            return offload(() -> passwordEncoder.encode(request.newPassword()))
                                    .flatMap(hash -> {
                                        user.setPassword(hash);
                                        return userRepository.save(user);
                                    });
                        }))
                .flatMap(user -> tokenRedisService.revokeAllUserTokensReactive(email))
                .doOnSuccess(v -> log.info("Password changed for user: {}", email));
    }

    // ── Exceptions ────────────────────────────────────────────────────────────
    public static class EmailAlreadyExistsException     extends RuntimeException { public EmailAlreadyExistsException(String e)     { super("Email already registered: " + e); } }
    public static class UsernameAlreadyExistsException  extends RuntimeException { public UsernameAlreadyExistsException(String u)   { super("Username already taken: " + u); } }
    public static class InvalidCurrentPasswordException extends RuntimeException { public InvalidCurrentPasswordException(String m)  { super(m); } }
    public static class InvalidRefreshTokenException    extends RuntimeException { public InvalidRefreshTokenException(String m)     { super(m); } }
}
