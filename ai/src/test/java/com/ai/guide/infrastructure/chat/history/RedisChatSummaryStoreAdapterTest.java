package com.ai.guide.infrastructure.chat.history;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatSummaryStoreAdapterTest {
    @Test
    @SuppressWarnings("unchecked")
    void persistsSummaryCursorAndCompressionCounterWithStableKeys() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("session-1:summary")).thenReturn("summary");
        when(values.get("session-1:compressed_count")).thenReturn("12");
        when(values.get("session-1:compress_count")).thenReturn("2");
        var store = new RedisChatSummaryStoreAdapter(redis);

        assertEquals("summary", store.getSummary("session-1"));
        assertEquals(12, store.getCompressedCount("session-1"));
        assertEquals(2, store.getCompressionCount("session-1"));
        store.saveSummary("session-1", "next", 15);
        store.incrementCompressionCount("session-1");
        store.clear("session-1");

        verify(values).set("session-1:summary", "next");
        verify(values).set("session-1:compressed_count", "15");
        verify(values).increment("session-1:compress_count", 1);
        verify(redis).delete("session-1:summary");
        verify(redis).delete("session-1:compressed_count");
        verify(redis).delete("session-1:compress_count");
    }
}
