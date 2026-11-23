package com.rollercoaster.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks revoked JWT tokens using Redis with an in-memory fallback when Redis is unavailable.
 */
@Service
public class TokenBlacklistService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TokenBlacklistService.class);
    private static final String BLACKLIST_KEY_PREFIX = "jwt:blacklist:";
    private static final long DEFAULT_TTL_SECONDS = 86400L;

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
        if (redisTemplate == null) {
            return;
        }
        try {
            long seconds = (ttl != null && !ttl.isNegative() && !ttl.isZero())
                    ? ttl.getSeconds()
                    : DEFAULT_TTL_SECONDS;
            redisTemplate.opsForValue().set(
                    BLACKLIST_KEY_PREFIX + token,
                    "revoked",
                    Duration.ofSeconds(seconds));
        } catch (RedisConnectionFailureException | IllegalStateException ex) {
            LOGGER.warn("Redis unavailable while blacklisting token; relying on in-memory fallback: {}", ex.getMessage());
        }
    }

    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (inMemoryBlacklist.contains(token)) {
            return true;
        }
        if (redisTemplate == null) {
            return false;
        }
        try {
            Boolean hasKey = redisTemplate.hasKey(BLACKLIST_KEY_PREFIX + token);
            return Boolean.TRUE.equals(hasKey);
        } catch (RedisConnectionFailureException | IllegalStateException ex) {
            LOGGER.warn("Redis unavailable while checking token blacklist: {}", ex.getMessage());
            return false;
        }
    }
}
