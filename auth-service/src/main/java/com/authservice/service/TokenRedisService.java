package com.authservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TokenRedisService {

    private final StringRedisTemplate redisTemplate;

    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";
    private static final String USER_TOKENS_PREFIX = "user_tokens:";

    public void saveRefreshToken(String token, String email, String userId, String role, long expiryMs) {
        String tokenKey = REFRESH_TOKEN_PREFIX + token;
        String userKey = USER_TOKENS_PREFIX + email;
        String value = userId + "|" + email + "|" + role;

        redisTemplate.opsForValue().set(tokenKey, value, expiryMs, TimeUnit.MILLISECONDS);
        redisTemplate.opsForSet().add(userKey, token);
        redisTemplate.expire(userKey, expiryMs, TimeUnit.MILLISECONDS);
    }

    public String getRefreshTokenValue(String token) {
        return redisTemplate.opsForValue().get(REFRESH_TOKEN_PREFIX + token);
    }

    public void deleteRefreshToken(String token, String email) {
        redisTemplate.delete(REFRESH_TOKEN_PREFIX + token);
        redisTemplate.opsForSet().remove(USER_TOKENS_PREFIX + email, token);
    }

    public void revokeAllUserTokens(String email) {
        String userKey = USER_TOKENS_PREFIX + email;
        Set<String> tokens = redisTemplate.opsForSet().members(userKey);

        if (tokens == null || tokens.isEmpty()) {
            redisTemplate.delete(userKey);
            return;
        }

        List<String> keysToDelete = new ArrayList<>();
        for (String token : tokens) {
            keysToDelete.add(REFRESH_TOKEN_PREFIX + token);
        }
        keysToDelete.add(userKey);

        redisTemplate.delete(keysToDelete); // single pipelined batch delete
    }

    private static final String BLACKLIST_PREFIX = "token_blacklist:";

    public void blacklistAccessToken(String token, long expiryMs) {
        if (expiryMs > 0) {
            redisTemplate.opsForValue().set(BLACKLIST_PREFIX + token, "revoked", expiryMs, TimeUnit.MILLISECONDS);
        }
    }

    public boolean isAccessTokenBlacklisted(String token) {
        return redisTemplate.hasKey(BLACKLIST_PREFIX + token);
    }
}
