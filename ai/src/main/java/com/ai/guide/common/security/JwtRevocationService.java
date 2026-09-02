package com.ai.guide.common.security;

import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * JWT 令牌作废与黑名单分布式管理服务
 *
 * 所属领域：common.security（安全与鉴权底座）
 * 架构职责：基于 Redis/内存管理登出或被禁用的 JWT JTI 黑名单，支持在令牌天然过期前执行主动注销与撤销。
 */
@Service
public class JwtRevocationService {

    private static final String KEY_PREFIX = "jwt:revoked:";
    private static final int MAX_LOCAL_REVOCATIONS = 10_000;

    private final RedisTemplate<String, String> redisTemplate;
    private final boolean redisEnabled;
    private final Map<String, Long> localRevoked = new ConcurrentHashMap<>();

    public JwtRevocationService(RedisTemplate<String, String> redisTemplate,
                                @Value("${jwt.revocation.redis-enabled:false}") boolean redisEnabled) {
        this.redisTemplate = redisTemplate;
        this.redisEnabled = redisEnabled;
    }

    public boolean isRevoked(String token, Claims claims) {
        String key = key(token, claims);
        long now = System.currentTimeMillis();
        cleanup(now);
        Long localExpiry = localRevoked.get(key);
        if (localExpiry != null && localExpiry > now) return true;
        if (!redisEnabled) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (RuntimeException unavailable) {
            // Revocation is fail-closed when shared state is explicitly enabled.
            return true;
        }
    }

    /** Returns false when a configured shared revocation store could not be written. */
    public boolean revoke(String token, Claims claims) {
        long expiresAt = claims == null || claims.getExpiration() == null
                ? 0
                : claims.getExpiration().getTime();
        long remaining = expiresAt - System.currentTimeMillis();
        if (remaining <= 0) return true;

        String key = key(token, claims);
        rememberLocally(key, expiresAt);
        if (!redisEnabled) return true;
        try {
            redisTemplate.opsForValue().set(key, "1", remaining, TimeUnit.MILLISECONDS);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    public boolean isRedisEnabled() {
        return redisEnabled;
    }

    private void rememberLocally(String key, long expiresAt) {
        long now = System.currentTimeMillis();
        cleanup(now);
        if (localRevoked.size() >= MAX_LOCAL_REVOCATIONS) {
            localRevoked.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .ifPresent(localRevoked::remove);
        }
        localRevoked.put(key, expiresAt);
    }

    private void cleanup(long now) {
        localRevoked.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    private String key(String token, Claims claims) {
        String jti = claims == null ? null : claims.getId();
        String identity = jti == null || jti.isBlank() ? sha256(token) : jti;
        return KEY_PREFIX + identity;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
