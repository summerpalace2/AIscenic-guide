package com.ai.guide.domain.user.service;


import com.ai.guide.common.security.JwtUtil;
import com.ai.guide.domain.user.model.User;
import com.ai.guide.domain.user.security.LegacyNodeScryptVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
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

class UserServiceLegacyPasswordMigrationTest {

    private static final String PASSWORD = "phase5b-utf8-密码";
    private static final String SALT = "0123456789abcdef0123456789abcdef";
    private static final String HASH = "b080e1ef86f32eaed77075f1608fd710049a2031c4ad253df7353491fc8294a4701538c813c1e137fcea5a16d3b34f174866a6886248003f37240c5fecfd6e68";

    @Test
    void successfulLegacyLoginRehashesAndIssuesToken() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        String username = "legacy-migration@example.invalid";
        User legacy = legacyUser(username);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(username)))
                .thenReturn(List.of(legacy));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jwtUtil.generateToken(eq("legacy-id"), eq(username), eq("USER")))
                .thenReturn("legacy-token");

        UserService service = new UserService(jdbcTemplate, jwtUtil);
        Map<String, Object> result = service.login(username, PASSWORD);

        assertEquals("legacy-token", result.get("token"));
        assertEquals("USER", result.get("role"));
        verify(jdbcTemplate).update(contains("password_salt = ''"), any(Object[].class));
        verify(jdbcTemplate).update(contains("password_scheme = 'BCRYPT'"), any(Object[].class));
    }

    @Test
    void wrongLegacyPasswordDoesNotMutateOrIssueToken() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        String username = "legacy-wrong@example.invalid";
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(username)))
                .thenReturn(List.of(legacyUser(username)));

        UserService service = new UserService(jdbcTemplate, jwtUtil);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.login(username, "phase5b-wrong-password"));

        assertTrue(error.getMessage().contains("密码错误"));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), anyString());
    }

    @Test
    void secondLoginUsesTheNewBcryptCredential() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        String username = "legacy-second-login@example.invalid";
        User legacy = legacyUser(username);
        User bcrypt = new User();
        bcrypt.setId("legacy-id");
        bcrypt.setUsername(username);
        bcrypt.setPasswordHash(new BCryptPasswordEncoder().encode(PASSWORD));
        bcrypt.setPasswordScheme("BCRYPT");
        bcrypt.setPasswordVersion("1");
        bcrypt.setPasswordParameters("{\"cost\":10}");
        bcrypt.setRehashRequired(false);
        bcrypt.setResetRequired(false);
        bcrypt.setRole("USER");
        bcrypt.setStatus("active");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(username)))
                .thenReturn(List.of(legacy), List.of(bcrypt));
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jwtUtil.generateToken(eq("legacy-id"), eq(username), eq("USER")))
                .thenReturn("legacy-token", "bcrypt-token");

        UserService service = new UserService(jdbcTemplate, jwtUtil);
        service.login(username, PASSWORD);
        Map<String, Object> second = service.login(username, PASSWORD);

        assertEquals("bcrypt-token", second.get("token"));
        verify(jwtUtil, org.mockito.Mockito.times(2))
                .generateToken(eq("legacy-id"), eq(username), eq("USER"));
    }

    private User legacyUser(String username) {
        User user = new User();
        user.setId("legacy-id");
        user.setUsername(username);
        user.setPasswordHash(HASH);
        user.setPasswordSalt(SALT);
        user.setPasswordScheme(LegacyNodeScryptVerifier.SCHEME);
        user.setPasswordVersion(LegacyNodeScryptVerifier.VERSION);
        user.setPasswordParameters(LegacyNodeScryptVerifier.PARAMETERS);
        user.setRehashRequired(true);
        user.setResetRequired(false);
        user.setRole("USER");
        user.setStatus("active");
        return user;
    }
}
