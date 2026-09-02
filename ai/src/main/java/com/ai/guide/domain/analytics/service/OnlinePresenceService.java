package com.ai.guide.domain.analytics.service;

import com.ai.guide.common.context.UserContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在线用户与活跃会话管理服务 (Online Presence Service)
 *
 * 所属领域：domain.analytics (运营监控与服务看板域)
 * 架构职责：利用 Redis Sorted Set (ZSET) 滑动时间窗口算法与 Hash 结构，实现轻量级、高并发的在线状态跟踪，防止把离线用户误统计为在线。
 *
 * 核心方法与职责：
 * 1. {@link #heartbeat(String, String, String)}: 刷新指定用户的存活过期时间（设置 90 秒滑动过期）。
 *    - 参数：userId (用户唯一标识), username (用户名/昵称), role (权限角色)。
 * 2. {@link #listOnlineUsers}: 清理已过期的僵尸会话，并返回当前处于有效存活窗口内的全部在线用户详情列表。
 *    - 返回结果：按最近活跃时间倒序排列的用户明细列表。
 */
@Service
public class OnlinePresenceService {
    private static final String ZSET_KEY = "scenic:online:users";
    private static final String USER_KEY_PREFIX = "scenic:online:user:";
    private static final long TTL_SECONDS = 90;

    private final RedisTemplate<String, String> redisTemplate;

    public OnlinePresenceService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void heartbeat() {
        heartbeat(UserContext.getUserId(), UserContext.getUsername(), UserContext.getRole());
    }

    public void heartbeat(String userId, String username, String role) {
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) return;
        long expiresAt = System.currentTimeMillis() + TTL_SECONDS * 1000;
        String key = USER_KEY_PREFIX + userId;
        Map<String, String> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("username", username == null ? userId : username);
        data.put("role", role == null ? "USER" : role);
        data.put("lastSeen", String.valueOf(System.currentTimeMillis()));
        redisTemplate.delete(key);
        redisTemplate.opsForHash().putAll(key, data);
        redisTemplate.expire(key, Duration.ofSeconds(TTL_SECONDS));
        redisTemplate.opsForZSet().add(ZSET_KEY, userId, expiresAt);
    }

    public List<Map<String, Object>> listOnlineUsers() {
        long now = System.currentTimeMillis();
        redisTemplate.opsForZSet().removeRangeByScore(ZSET_KEY, Double.NEGATIVE_INFINITY, now);
        Set<String> ids = redisTemplate.opsForZSet().rangeByScore(ZSET_KEY, now, Double.POSITIVE_INFINITY);
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
