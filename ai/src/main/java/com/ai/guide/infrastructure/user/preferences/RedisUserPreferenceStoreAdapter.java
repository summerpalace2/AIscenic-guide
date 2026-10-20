package com.ai.guide.infrastructure.user.preferences;

import com.ai.guide.domain.user.gateway.UserPreferenceStorePort;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;

/** Redis implementation of the user preference store. */
@Repository
public class RedisUserPreferenceStoreAdapter implements UserPreferenceStorePort {
    private static final String SLOTS_SUFFIX = ":slots";
    private static final String PREFERENCES_SUFFIX = ":preferences";
    private static final String INITIALIZED_FIELD = "prefs_initialized";
    private final RedisTemplate<String, String> redisTemplate;

    public RedisUserPreferenceStoreAdapter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Map<String, String> readSlots(String userId) { return readHash(slotsKey(userId)); }

    @Override
    public String readSlot(String userId, String slotName) {
        Object value = redisTemplate.opsForHash().get(slotsKey(userId), slotName);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    public void writeSlot(String userId, String slotName, String value) {
        redisTemplate.opsForHash().put(slotsKey(userId), slotName, value);
    }

    @Override
    public void clearSlots(String userId) { redisTemplate.delete(slotsKey(userId)); }

    @Override
    public Map<String, String> readManualPreferences(String userId) { return readHash(preferencesKey(userId)); }

    @Override
    public void writeManualPreferences(String userId, Map<String, String> preferences) {
        String key = preferencesKey(userId);
        redisTemplate.opsForHash().putAll(key, preferences);
        redisTemplate.opsForHash().put(key, INITIALIZED_FIELD, "1");
    }

    @Override
    public void clearManualPreferences(String userId) {
        String key = preferencesKey(userId);
        redisTemplate.delete(key);
        // Preserve the marker so deleted defaults cannot reappear during initialization.
        redisTemplate.opsForHash().put(key, INITIALIZED_FIELD, "1");
    }

    private Map<String, String> readHash(String key) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key);
        Map<String, String> result = new LinkedHashMap<>();
        if (values != null) values.forEach((field, value) -> result.put(String.valueOf(field), String.valueOf(value)));
        return result;
    }

    private static String slotsKey(String userId) { return "user:" + userId + SLOTS_SUFFIX; }
    private static String preferencesKey(String userId) { return "user:" + userId + PREFERENCES_SUFFIX; }
}
