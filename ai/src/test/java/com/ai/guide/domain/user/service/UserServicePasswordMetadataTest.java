package com.ai.guide.domain.user.service;


import com.ai.guide.common.security.JwtUtil;
import com.ai.guide.domain.user.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServicePasswordMetadataTest {

    @Test
    void registrationWritesExplicitBcryptMetadata() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        String username = "metadata-user";
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(username))).thenReturn(0);
        when(jwtUtil.generateToken(anyString(), eq(username), eq("USER"))).thenReturn("test-token");
        UserService service = new UserService(jdbcTemplate, jwtUtil);

        Map<String, Object> result = service.register(username, "test-password");

        verify(jdbcTemplate).update(contains("password_scheme"), any(Object[].class));
        assertEquals("USER", result.get("role"));
        assertEquals("test-token", result.get("token"));
    }

    @Test
    void unsupportedLegacySchemeFailsClosedBeforePasswordVerification() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        String username = "legacy-user";
        User legacy = new User();
        legacy.setId("legacy-id");
        legacy.setUsername(username);
        legacy.setPasswordHash("unsupported-hash");
        legacy.setPasswordScheme("LEGACY_SCRYPT");
        legacy.setPasswordVersion("node-scrypt-v1");
        legacy.setRehashRequired(true);
        legacy.setResetRequired(false);
        legacy.setRole("USER");
        legacy.setStatus("active");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(username)))
                .thenReturn(List.of(legacy));
        UserService service = new UserService(jdbcTemplate, jwtUtil);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.login(username, "test-password"));

        assertTrue(error.getMessage().contains("重置"));
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), anyString());
    }
}
