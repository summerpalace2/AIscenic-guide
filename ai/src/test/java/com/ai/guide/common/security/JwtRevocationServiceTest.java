package com.ai.guide.common.security;



import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JwtRevocationServiceTest {

    private static final String SECRET = "phase5a-jwt-test-secret-0123456789";

    @Test
    void localRevocationRejectsTokenBeforeExpiry() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 7);
        String token = jwtUtil.generateToken("user-1", "traveler", "USER");
        Claims claims = jwtUtil.parse(token);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        JwtRevocationService service = new JwtRevocationService(redis, false);

        assertFalse(service.isRevoked(token, claims));
        assertTrue(service.revoke(token, claims));
        assertTrue(service.isRevoked(token, claims));
    }

    @Test
    void enabledRedisFailureFailsClosedForChecks() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 7);
        String token = jwtUtil.generateToken("user-1", "traveler", "USER");
        Claims claims = jwtUtil.parse(token);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        when(redis.hasKey(anyString())).thenThrow(new RuntimeException("redis unavailable"));
        JwtRevocationService service = new JwtRevocationService(redis, true);

        assertTrue(service.isRevoked(token, claims));
    }

    @Test
    void enabledRedisWriteFailureIsReportedToLogoutCaller() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 7);
        String token = jwtUtil.generateToken("user-1", "traveler", "USER");
        Claims claims = jwtUtil.parse(token);
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        doThrow(new RuntimeException("redis unavailable"))
                .when(operations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        JwtRevocationService service = new JwtRevocationService(redis, true);

        assertFalse(service.revoke(token, claims));
    }
}
