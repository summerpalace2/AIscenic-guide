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
    @Test
    void localCapacityNeverEvictsLiveRevocationsAndReportsOverflow() {
        var service=new JwtRevocationService(mock(RedisTemplate.class),false);
        long expiration=System.currentTimeMillis()+600000;
        for(int i=0;i<10000;i++) {
            Claims claims=mock(Claims.class);when(claims.getId()).thenReturn("capacity-"+i);when(claims.getExpiration()).thenReturn(new java.util.Date(expiration));
            assertTrue(service.revoke("token-"+i,claims));
        }
        Claims overflow=mock(Claims.class);when(overflow.getId()).thenReturn("overflow");when(overflow.getExpiration()).thenReturn(new java.util.Date(expiration));
        assertFalse(service.revoke("overflow-token",overflow));
        assertTrue(service.isRevoked("overflow-token",overflow));
        Claims first=mock(Claims.class);when(first.getId()).thenReturn("capacity-0");when(first.getExpiration()).thenReturn(new java.util.Date(expiration));
        assertTrue(service.isRevoked("token-0",first));
    }
}
