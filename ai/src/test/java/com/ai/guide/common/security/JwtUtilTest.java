package com.ai.guide.common.security;


import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtUtilTest {

    private static final String SECRET = "phase5a-jwt-test-secret-0123456789";

    @Test
    void generatedTokenContainsUniqueIdAndCurrentIdentityClaims() {
        JwtUtil jwtUtil = new JwtUtil(SECRET, 7);

        String token = jwtUtil.generateToken("user-1", "traveler", "USER");
        Claims claims = jwtUtil.parse(token);

        assertNotNull(claims);
        assertNotNull(claims.getId());
        assertTrue(!claims.getId().isBlank());
        assertEquals("user-1", claims.getSubject());
        assertEquals("traveler", claims.get("username", String.class));
        assertEquals("USER", claims.get("role", String.class));
        assertNull(jwtUtil.parse("not-a-jwt"));
    }

    @Test
    void missingOrShortSecretFailsClosed() {
        assertThrows(IllegalStateException.class, () -> new JwtUtil("too-short", 7));
        assertThrows(IllegalStateException.class, () -> new JwtUtil(null, 7));
    }
}
