package com.rollercoaster.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TokenBlacklistService {

    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";
    private final StringRedisTemplate redisTemplate;
    private final Set<String> inMemoryBlacklist = ConcurrentHashMap.newKeySet();

    public TokenBlacklistService(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void blacklistToken(String token, Duration ttl) {
        if (token == null || token.isBlank()) {
            return;
        }
        inMemoryBlacklist.add(token);
        if (redisTemplate != null) {
            try {
                long seconds = (ttl != null && !ttl.isNegative() && !ttl.isZero()) ? ttl.getSeconds() : 86400;
                redisTemplate.opsForValue().set(BLACKLIST_KEY_PREFIX + token, "revoked", Duration.ofSeconds(seconds));
            } catch (Exception ignored) {
                // In-memory blacklist fallback when Redis is offline
            }
        }
    }

    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (inMemoryBlacklist.contains(token)) {
            return true;
        }
        if (redisTemplate != null) {
            try {
                Boolean hasKey = redisTemplate.hasKey(BLACKLIST_KEY_PREFIX + token);
                return Boolean.TRUE.equals(hasKey);
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }
}
