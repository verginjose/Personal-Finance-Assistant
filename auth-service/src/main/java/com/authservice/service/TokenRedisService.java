package com.authservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class TokenRedisService {

    private final ReactiveStringRedisTemplate redisTemplate;

    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";
    private static final String USER_TOKENS_PREFIX = "user_tokens:";
    private static final String BLACKLIST_PREFIX = "token_blacklist:";
    private static final String GRACE_PREFIX = "refresh_grace:";
    private static final String SESSION_VERSION_PREFIX = "session_version:";
    private static final String LOGIN_FAIL_PREFIX = "login_fail:";

    /** A failed-login window that is short enough to be safe, long enough to matter. */
    private static final int MAX_LOGIN_FAILURES = 10;
    private static final Duration LOGIN_FAIL_WINDOW = Duration.ofMinutes(15);

    /**
     * The user's current session version. Access tokens carry the version they
     * were minted with; bumping it invalidates every live access token without
     * needing to enumerate them.
     */
    public Mono<Long> getSessionVersion(String userId) {
        if (userId == null || userId.isBlank()) return Mono.just(0L);
        return redisTemplate.opsForValue().get(SESSION_VERSION_PREFIX + userId)
                .map(v -> {
                    try { return Long.parseLong(v); } catch (NumberFormatException e) { return 0L; }
                })
                .defaultIfEmpty(0L);
    }

    public Mono<Long> bumpSessionVersion(String userId) {
        return redisTemplate.opsForValue().increment(SESSION_VERSION_PREFIX + userId)
                .defaultIfEmpty(0L);
    }

    public Mono<Boolean> isLoginLocked(String email) {
        return redisTemplate.opsForValue().get(LOGIN_FAIL_PREFIX + normalize(email))
                .map(v -> {
                    try { return Long.parseLong(v) >= MAX_LOGIN_FAILURES; }
                    catch (NumberFormatException e) { return false; }
                })
                .defaultIfEmpty(false);
    }

    public Mono<Long> recordLoginFailure(String email) {
        String key = LOGIN_FAIL_PREFIX + normalize(email);
        return redisTemplate.opsForValue().increment(key)
                .flatMap(count -> redisTemplate.expire(key, LOGIN_FAIL_WINDOW).thenReturn(count))
                .defaultIfEmpty(1L);
    }

    public Mono<Void> clearLoginFailures(String email) {
        return redisTemplate.delete(LOGIN_FAIL_PREFIX + normalize(email)).then();
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /**
     * Refresh rotation is single-use (GETDEL). Two tabs or parallel requests in
     * the same browser can replay the same token; the winner rotates, the loser
     * must be able to recover the freshly issued pair instead of being logged
     * out. The grace value is short-lived on purpose.
     */
    public Mono<Void> saveRefreshGraceReactive(String consumedToken, String value) {
        return redisTemplate.opsForValue()
                .set(GRACE_PREFIX + consumedToken, value, Duration.ofSeconds(60))
                .then();
    }

    public Mono<String> getRefreshGraceReactive(String consumedToken) {
        return redisTemplate.opsForValue().get(GRACE_PREFIX + consumedToken);
    }

    public Mono<Void> saveRefreshTokenReactive(String token, String email, String userId, String role, long expiryMs) {
        String tokenKey = REFRESH_TOKEN_PREFIX + token;
        String userKey = USER_TOKENS_PREFIX + email;
        String value = userId + "|" + email + "|" + role;

        return redisTemplate.opsForValue().set(tokenKey, value, Duration.ofMillis(expiryMs))
                .then(redisTemplate.opsForSet().add(userKey, token))
                .then(redisTemplate.expire(userKey, Duration.ofMillis(expiryMs)))
                .then();
    }

    public Mono<String> getRefreshTokenValueReactive(String token) {
        return redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + token);
    }

    /**
     * Atomically reads and deletes the refresh token (Redis GETDEL). Rotation
     * must be consume-and-issue: a plain GET followed by a later DELETE lets
     * two concurrent replays of the same token both rotate it and leave two
     * live sessions. Only the caller that gets a non-empty value may rotate.
     */
    public Mono<String> consumeRefreshTokenReactive(String token) {
        return redisTemplate.opsForValue().getAndDelete(REFRESH_TOKEN_PREFIX + token);
    }

    public Mono<Void> removeUserTokenReactive(String token, String email) {
        return redisTemplate.opsForSet().remove(USER_TOKENS_PREFIX + email, token).then();
    }

    public Mono<Void> deleteRefreshTokenReactive(String token, String email) {
        return redisTemplate.delete(REFRESH_TOKEN_PREFIX + token)
                .then(redisTemplate.opsForSet().remove(USER_TOKENS_PREFIX + email, token))
                .then();
    }

    public Mono<Void> revokeAllUserTokensReactive(String email) {
        String userKey = USER_TOKENS_PREFIX + email;
        return redisTemplate.opsForSet().members(userKey)
                .flatMap(token -> redisTemplate.delete(REFRESH_TOKEN_PREFIX + token))
                .then(redisTemplate.delete(userKey))
                .then();
    }

    public Mono<Void> blacklistAccessTokenReactive(String token, long expiryMs) {
        if (expiryMs > 0) {
            return redisTemplate.opsForValue().set(BLACKLIST_PREFIX + token, "revoked", Duration.ofMillis(expiryMs))
                    .then();
        }
        return Mono.empty();
    }
}
