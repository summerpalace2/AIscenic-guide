package com.ai.guide.infrastructure.chat.history;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisChatHistoryStoreAdapterTest {
    @Test
    @SuppressWarnings("unchecked")
    void scopesOwnershipAndRemovesAllSessionArtifacts() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ZSetOperations<String, String> indexes = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(indexes);
        when(indexes.score("chat:sessions:user-1", "user-1:session-1")).thenReturn(1d);
        var store = new RedisChatHistoryStoreAdapter(redis);

        assertTrue(store.ownsSession("user-1", "session-1"));
        store.deleteSession("user-1", "user-1:session-1");

        verify(redis).execute(com.ai.guide.infrastructure.chat.memory.RedisChatSessionScripts.CLEAR,
                java.util.List.of("chat:history:user-1:session-1","user-1:session-1:summary_snapshot",
                        "user-1:session-1:summary","user-1:session-1:compressed_count","user-1:session-1:compress_count",
                        "user-1:session-1:write_epoch","chat:sessions:user-1"),"user-1:session-1");
    }
}
