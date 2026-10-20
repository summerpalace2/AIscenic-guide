package com.ai.guide.infrastructure.user.preferences;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisUserPreferenceStoreAdapterTest {
    @Test
    @SuppressWarnings("unchecked")
    void keepsUserScopedKeysAndPreservesPreferenceInitializationMarker() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("user:u-1:slots")).thenReturn(Map.of("area", "洪崖洞"));
        when(hashes.get("user:u-1:slots", "area")).thenReturn("洪崖洞");
        var store = new RedisUserPreferenceStoreAdapter(redis);

        assertEquals(Map.of("area", "洪崖洞"), store.readSlots("u-1"));
        assertEquals("洪崖洞", store.readSlot("u-1", "area"));
        store.writeSlot("u-1", "duration", "半天");
        store.writeManualPreferences("u-1", Map.of("language", "en"));
        store.clearManualPreferences("u-1");

        verify(hashes).put("user:u-1:slots", "duration", "半天");
        verify(hashes).putAll("user:u-1:preferences", Map.of("language", "en"));
        org.mockito.Mockito.verify(hashes, org.mockito.Mockito.times(2))
                .put("user:u-1:preferences", "prefs_initialized", "1");
        verify(redis).delete("user:u-1:preferences");
    }
}
