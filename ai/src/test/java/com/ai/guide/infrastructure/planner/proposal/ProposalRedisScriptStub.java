package com.ai.guide.infrastructure.planner.proposal;

import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Models only the two single-key atomic commands; real Redis execution remains an opt-in test. */
final class ProposalRedisScriptStub {
    static void install(StringRedisTemplate redis, ValueOperations<String, String> values) {
        when(redis.opsForValue()).thenReturn(values);
        doAnswer(call -> {
            List<String> keys = call.getArgument(1);
            Object[] args = (Object[]) call.getRawArguments()[2];
            String key = keys.get(0);
            synchronized (redis) {
                if (call.getArgument(0) == CaffeineRedisProposalRepository.PUBLISH_SNAPSHOT) {
                    if (args[0].equals(values.get(key))) return 0L;
                    values.set(key, (String) args[1], Duration.ofMillis(Long.parseLong((String) args[2])));
                    return 1L;
                }
                if (args[0].equals(values.get(key))) return Boolean.TRUE.equals(redis.delete(key)) ? 1L : 0L;
                return 0L;
            }
        }).when(redis).execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(), any(Object[].class));
    }
}
