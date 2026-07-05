package com.apigateway.auth.service;

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
