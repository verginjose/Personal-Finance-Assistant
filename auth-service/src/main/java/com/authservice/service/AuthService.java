package com.authservice.service;

import com.authservice.dto.AuthDtos.*;
import com.authservice.model.Role;
import com.authservice.model.User;
import com.authservice.repository.UserRepository;
import com.authservice.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    private static String normalizeCurrency(String code) {
        if (code == null || code.isBlank()) return "INR";
        String normalized = code.trim().toUpperCase();
        return normalized.matches("[A-Z]{3}") ? normalized : "INR";
    }

    @Transactional(readOnly = true)
    public Mono<LoginResponse> login(LoginRequest request) {
        return tokenRedisService.isLoginLocked(request.email())
                .flatMap(locked -> {
                    if (locked) {
                        // Deliberately generic: do not reveal whether the
                        // address is registered or merely locked.
                        return Mono.error(new TooManyLoginAttemptsException(
                                "Too many failed attempts. Try again later."));
                    }
                    return userRepository.findByEmail(request.email())
                            .switchIfEmpty(Mono.error(new InvalidCredentialsException("Invalid email or password")))
                            .flatMap(user -> offload(() -> passwordEncoder.matches(request.password(), user.getPassword()))
                                    .flatMap(matches -> {
                                        if (!matches) {
                                            return tokenRedisService.recordLoginFailure(request.email())
                                                    .then(Mono.error(new InvalidCredentialsException("Invalid email or password")));
                                        }
                                        return tokenRedisService.clearLoginFailures(request.email())
                                                .then(tokenRedisService.getSessionVersion(user.getId().toString()))
                                                .flatMap(sessionVersion -> {
                                                    String token = jwtService.generateToken(user, sessionVersion);
                                                    String refreshToken = UUID.randomUUID().toString();
                                                    return tokenRedisService.saveRefreshTokenReactive(
                                                                    refreshToken, user.getEmail(), user.getId().toString(),
                                                                    user.getRole().name(), refreshExpirationMs)
                                                            .then(Mono.fromCallable(() -> {
                                                                log.info("User logged in: {} [{}]", user.getEmail(), user.getRole());
                                                                return new LoginResponse(token, refreshToken, user.getId().toString(),
                                                                        user.getEmail(), user.getActualUsername(), user.getRole().name(),
                                                                        normalizeCurrency(user.getDefaultCurrency()));
                                                            }).subscribeOn(Schedulers.boundedElastic()));
                                                });
                                    }));
                });
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
                                                // Self-registration is always a USER.
                                                .role(Role.USER)
                                                .defaultCurrency(normalizeCurrency(request.defaultCurrency()))
                                                .build()))
                                        .map(saved -> {
                                            log.info("Registered: {} (@{})", saved.getEmail(), saved.getActualUsername());
                                            return new RegisterResponse(saved.getId().toString(), saved.getEmail(),
                                                    saved.getActualUsername(), saved.getRole().name(),
                                                    normalizeCurrency(saved.getDefaultCurrency()));
                                        });
                            });
                });
    }

    public Mono<LoginResponse> refresh(String refreshToken) {
        return refresh(new RefreshTokenRequest(refreshToken));
    }

    public Mono<LoginResponse> refresh(RefreshTokenRequest request) {
        String oldToken = request.refreshToken();
        // GETDEL: exactly one concurrent replay of a token wins the rotation.
        // A loser falls back to the 60 s grace record written by the winner, so
        // two tabs refreshing at once both end up with the same live session.
        return tokenRedisService.consumeRefreshTokenReactive(oldToken)
                .flatMap(value -> rotate(oldToken, value))
                .switchIfEmpty(Mono.defer(() -> fromRefreshGrace(oldToken, 10)));
    }

    private Mono<LoginResponse> rotate(String oldToken, String value) {
        String[] parts = value.split("\\|");
        if (parts.length < 3) {
            return Mono.error(new InvalidRefreshTokenException("Malformed refresh token session"));
        }
        String userId = parts[0], email = parts[1], role = parts[2];

        return tokenRedisService.removeUserTokenReactive(oldToken, email)
                .then(tokenRedisService.getSessionVersion(userId))
                .flatMap(sessionVersion -> {
                    String newAccessToken = jwtService.generateToken(email, userId, role, sessionVersion);
                    String newRefreshToken = UUID.randomUUID().toString();
                    String graceValue = newAccessToken + "|" + newRefreshToken + "|"
                            + userId + "|" + email + "|" + role;
                    return tokenRedisService.saveRefreshTokenReactive(
                                    newRefreshToken, email, userId, role, refreshExpirationMs)
                            .then(tokenRedisService.saveRefreshGraceReactive(oldToken, graceValue))
                            .thenReturn(new LoginResponse(newAccessToken, newRefreshToken, userId, email, "", role, null))
                            .doOnSuccess(r -> log.info("Tokens rotated for user: {}", email));
                });
    }

    /**
     * Recover the pair issued when a concurrent request already rotated this
     * token. Polls briefly so the loser does not beat the winner's grace write.
     */
    private Mono<LoginResponse> fromRefreshGrace(String oldToken, int attemptsLeft) {
        return tokenRedisService.getRefreshGraceReactive(oldToken)
                .map(value -> {
                    String[] parts = value.split("\\|");
                    return new LoginResponse(parts[0], parts[1], parts[2], parts[3], "", parts[4], null);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    if (attemptsLeft <= 0) {
                        return Mono.error(new InvalidRefreshTokenException("Invalid or expired refresh token"));
                    }
                    return Mono.delay(java.time.Duration.ofMillis(40))
                            .then(fromRefreshGrace(oldToken, attemptsLeft - 1));
                }));
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
    public Mono<Void> changePassword(String email, ChangePasswordRequest request, String currentAccessToken) {
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
                .flatMap(user -> {
                    // Kill every refresh token and bump the session version, so
                    // live access tokens (default 15 min) die immediately too.
                    Mono<Void> revokeRefresh = tokenRedisService.revokeAllUserTokensReactive(email);
                    Mono<Void> bumpVersion = tokenRedisService.bumpSessionVersion(user.getId().toString()).then();
                    Mono<Void> blacklistCurrent = currentAccessToken == null ? Mono.empty()
                            : blacklistBearer(currentAccessToken);
                    return revokeRefresh.then(bumpVersion).then(blacklistCurrent);
                })
                .doOnSuccess(v -> log.info("Password changed and sessions revoked for user: {}", email));
    }

    private Mono<Void> blacklistBearer(String bearer) {
        try {
            var claims = jwtService.extractAllClaims(bearer);
            long ttl = claims.getExpiration().getTime() - System.currentTimeMillis();
            return tokenRedisService.blacklistAccessTokenReactive(bearer, ttl);
        } catch (Exception e) {
            log.warn("Failed to blacklist the current access token: {}", e.getMessage());
            return Mono.empty();
        }
    }

    /** A two-minute, single-purpose ticket for the SSE stream. */
    public Mono<String> streamTicket(String email, String userId, String role) {
        return tokenRedisService.getSessionVersion(userId)
                .map(sv -> jwtService.generateStreamTicket(email, userId, role, sv));
    }

    // ── Exceptions ────────────────────────────────────────────────────────────
    public static class EmailAlreadyExistsException     extends RuntimeException { public EmailAlreadyExistsException(String e)     { super("Email already registered: " + e); } }
    public static class UsernameAlreadyExistsException  extends RuntimeException { public UsernameAlreadyExistsException(String u)   { super("Username already taken: " + u); } }
    public static class InvalidCurrentPasswordException extends RuntimeException { public InvalidCurrentPasswordException(String m)  { super(m); } }
    public static class InvalidCredentialsException     extends RuntimeException { public InvalidCredentialsException(String m)     { super(m); } }
    public static class InvalidRefreshTokenException    extends RuntimeException { public InvalidRefreshTokenException(String m)     { super(m); } }
    public static class TooManyLoginAttemptsException   extends RuntimeException { public TooManyLoginAttemptsException(String m)    { super(m); } }
}
