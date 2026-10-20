package com.ai.guide.infrastructure.chat.memory;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.chat.service.ChatContextBudgetPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import java.util.*;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RedisChatMemorySafetySnapshotTest {
    @Test @SuppressWarnings("unchecked") void legacySnapshotRemainsReadableAndClearRemovesIndependentSafetyState() throws Exception {
        UserContext.clear(); var redis = mock(RedisTemplate.class); var values = mock(ValueOperations.class);
        var stored = new HashMap<String, String>(); var mapper = new ObjectMapper();
        when(redis.opsForValue()).thenReturn(values); when(values.get(anyString())).thenAnswer(i -> stored.get(i.getArgument(0)));
        when(redis.delete(anyString())).thenAnswer(i -> stored.remove(i.getArgument(0)) != null);
        doAnswer(i -> { List<String> keys=i.getArgument(1);for(int n=0;n<5;n++)stored.remove(keys.get(n));
            stored.put(keys.get(5),"1");return 1L; }).when(redis).execute(eq(RedisChatSessionScripts.CLEAR),anyList(),any(Object[].class));
        var memory = new RedisChatMemory(redis, mapper, mock(ExecutorService.class));
        stored.put("s:summary_snapshot", "{\"summary\":\"legacy\",\"coveredThrough\":12}");
        assertEquals(12, memory.summarySnapshot("s").coveredThrough()); assertTrue(memory.summarySnapshot("s").criticalConstraints().isEmpty());
        stored.put("s:summary_snapshot", mapper.writeValueAsString(new RedisChatMemory.SummarySnapshot("summary", 20,
                List.of("我对花生过敏"), Map.of("我对花生过敏", 10L))));
        assertEquals(List.of("我对花生过敏"), memory.summarySnapshot("s").criticalConstraints());
        memory.clear("s"); assertTrue(memory.summarySnapshot("s").criticalConstraints().isEmpty()); assertEquals(0, memory.summarySnapshot("s").coveredThrough());
    }
    @Test @SuppressWarnings("unchecked") void redisReadFailureIsDistinguishableFromMissingSession() {
        var redis = mock(RedisTemplate.class); var values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values); when(values.get(anyString())).thenThrow(new IllegalStateException("fixture connection unavailable"));
        var memory = new RedisChatMemory(redis, new ObjectMapper(), mock(ExecutorService.class));
        assertThrows(ChatContextBudgetPolicy.ContextReadException.class, () -> memory.summarySnapshot("s"));
    }
    @Test @SuppressWarnings("unchecked") void atomicBudgetRejectionIsVisibleAndOriginalStatementIsNotClippedBeforeStorage() {
        var redis = mock(RedisTemplate.class); var mapper = new ObjectMapper();
        when(redis.opsForValue()).thenReturn(mock(ValueOperations.class));
        doReturn("OVERFLOW").when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        var memory = new RedisChatMemory(redis, mapper, mock(ExecutorService.class));
        String statement = "我对花生过敏，不能吃花生" + "长说明".repeat(3000);
        assertThrows(ChatContextBudgetPolicy.ContextBudgetException.class,
                () -> memory.rememberCriticalConstraints("s", List.of(statement), 6000, 2_000_000_000_000L));
        var invocation = mockingDetails(redis).getInvocations().stream().filter(i -> i.getMethod().getName().equals("execute")).findFirst().orElseThrow();
        String payload = (String) invocation.getArguments()[2];
        assertEquals(statement, assertDoesNotThrow(() -> mapper.readTree(payload)).get(0).asText());
    }
}
