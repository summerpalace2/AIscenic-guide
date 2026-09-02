package com.ai.guide.domain.user.api;


import com.ai.guide.common.security.JwtRevocationService;
import com.ai.guide.common.security.JwtUtil;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.user.service.UserService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    private static final String SECRET = "phase5a-jwt-test-secret-0123456789";

    private final UserService userService = mock(UserService.class);
    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 7);
    private final JwtRevocationService revocationService = mock(JwtRevocationService.class);
    private final AuthController controller = new AuthController(userService, jwtUtil, revocationService);

    @Test
    void logoutRevokesTheCurrentToken() {
        String token = jwtUtil.generateToken("user-1", "traveler", "USER");
        when(revocationService.revoke(eq(token), any(Claims.class))).thenReturn(true);

        Result<Void> result = controller.logout("Bearer " + token);

        assertEquals(200, result.getCode());
        assertTrue(result.isSuccess());
        verify(revocationService).revoke(eq(token), any(Claims.class));
    }

    @Test
    void malformedOrMissingTokenCannotLogout() {
        Result<Void> missing = controller.logout(null);
        Result<Void> malformed = controller.logout("Basic not-a-bearer-token");

        assertEquals(401, missing.getCode());
        assertFalse(missing.isSuccess());
        assertEquals(401, malformed.getCode());
        assertFalse(malformed.isSuccess());
        verify(revocationService, never()).revoke(any(), any());
    }

    @Test
    void invalidTokenCannotLogout() {
        Result<Void> result = controller.logout("Bearer malformed");

        assertEquals(401, result.getCode());
        assertFalse(result.isSuccess());
        verify(revocationService, never()).revoke(any(), any());
    }

    @Test
    void sharedRevocationFailureReturnsServiceUnavailable() {
        String token = jwtUtil.generateToken("user-1", "traveler", "USER");
        when(revocationService.revoke(eq(token), any(Claims.class))).thenReturn(false);

        Result<Void> result = controller.logout("Bearer " + token);

        assertEquals(503, result.getCode());
        assertFalse(result.isSuccess());
    }
}
