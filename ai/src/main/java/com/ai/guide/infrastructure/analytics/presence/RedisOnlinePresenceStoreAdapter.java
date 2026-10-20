package com.ai.guide.infrastructure.analytics.presence;

import com.ai.guide.domain.analytics.gateway.OnlinePresenceStorePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Redis implementation of online-presence storage. */
@Repository
public class RedisOnlinePresenceStoreAdapter implements OnlinePresenceStorePort {
    private static final String ZSET_KEY = "scenic:online:users";
    private static final String USER_KEY_PREFIX = "scenic:online:user:";
    private final RedisTemplate<String, String> redisTemplate;
    private final Duration ttl;

    public RedisOnlinePresenceStoreAdapter(RedisTemplate<String, String> redisTemplate,
                                           @Value("${analytics.online-presence.ttl:90s}") Duration ttl) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl.isNegative() || ttl.isZero() ? Duration.ofSeconds(90) : ttl;
    }

    @Override
    public void heartbeat(String userId, String username, String role, long lastSeenEpochMillis, long expiresAtEpochMillis) {
        String key = USER_KEY_PREFIX + userId;
        Map<String, String> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("username", username == null ? userId : username);
        data.put("role", role == null ? "USER" : role);
        data.put("lastSeen", String.valueOf(lastSeenEpochMillis));
        redisTemplate.delete(key);
        redisTemplate.opsForHash().putAll(key, data);
        redisTemplate.expire(key, ttl);
        redisTemplate.opsForZSet().add(ZSET_KEY, userId, expiresAtEpochMillis);
    }

    @Override
    public List<Map<String, Object>> findActive(long nowEpochMillis) {
        redisTemplate.opsForZSet().removeRangeByScore(ZSET_KEY, Double.NEGATIVE_INFINITY, nowEpochMillis);
        Set<String> ids = redisTemplate.opsForZSet().rangeByScore(ZSET_KEY, nowEpochMillis, Double.POSITIVE_INFINITY);
        List<Map<String, Object>> users = new ArrayList<>();
        if (ids == null) return users;
        for (String id : ids) {
            Map<Object, Object> raw = redisTemplate.opsForHash().entries(USER_KEY_PREFIX + id);
            if (raw.isEmpty()) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", String.valueOf(raw.getOrDefault("userId", id)));
            item.put("username", String.valueOf(raw.getOrDefault("username", id)));
            item.put("role", String.valueOf(raw.getOrDefault("role", "USER")));
            item.put("lastSeen", raw.get("lastSeen"));
            users.add(item);
        }
        users.sort(Comparator.comparing(item -> String.valueOf(item.get("lastSeen")), Comparator.reverseOrder()));
        return users;
    }
}
