package com.ai.guide.domain.admin.config;


import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminInitConfigTest {

    private static final String ADMIN_COUNT_SQL =
            "SELECT COUNT(*) FROM users WHERE role IN ('ADMIN','SUPER_ADMIN')";
    private static final String USERNAME_COUNT_SQL =
            "SELECT COUNT(*) FROM users WHERE username = ?";

    @Test
    void disabledModeDoesNotCreateAnAdministrator() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(eq(ADMIN_COUNT_SQL), eq(Integer.class))).thenReturn(0);
        AdminInitConfig config = config("disabled", "", "");

        ApplicationRunner runner = config.adminInitializer(jdbcTemplate);

        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
        verify(jdbcTemplate).queryForObject(eq(ADMIN_COUNT_SQL), eq(Integer.class));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void oneTimeModeRejectsMissingOrShortExternalPassword() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(eq(ADMIN_COUNT_SQL), eq(Integer.class))).thenReturn(0);
        AdminInitConfig config = config("one-time", "bootstrap-admin", "short");
        ApplicationRunner runner = config.adminInitializer(jdbcTemplate);

        assertThrows(IllegalStateException.class,
                () -> runner.run(new DefaultApplicationArguments()));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void oneTimeModeCreatesBcryptMetadataFromExternalCredentials() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        String username = "bootstrap-admin";
        when(jdbcTemplate.queryForObject(eq(ADMIN_COUNT_SQL), eq(Integer.class))).thenReturn(0);
        when(jdbcTemplate.queryForObject(eq(USERNAME_COUNT_SQL), eq(Integer.class), eq(username))).thenReturn(0);
        AdminInitConfig config = config("one-time", username, "phase5a-admin-password");
        ApplicationRunner runner = config.adminInitializer(jdbcTemplate);

        runner.run(new DefaultApplicationArguments());

        verify(jdbcTemplate).update(contains("password_scheme"), any(Object[].class));
    }

    @Test
    void provisionModeCanCreateAnAdditionalAdministrator() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        String username = "second-admin@example.com";
        when(jdbcTemplate.queryForObject(eq(ADMIN_COUNT_SQL), eq(Integer.class))).thenReturn(1);
        when(jdbcTemplate.queryForObject(eq(USERNAME_COUNT_SQL), eq(Integer.class), eq(username))).thenReturn(0);
        AdminInitConfig config = config("provision", username, "phase5a-admin-password");
        ApplicationRunner runner = config.adminInitializer(jdbcTemplate);

        runner.run(new DefaultApplicationArguments());

        verify(jdbcTemplate).update(contains("'ADMIN'"), any(Object[].class));
    }

    private AdminInitConfig config(String mode, String username, String password) {
        AdminInitConfig config = new AdminInitConfig();
        ReflectionTestUtils.setField(config, "bootstrapMode", mode);
        ReflectionTestUtils.setField(config, "bootstrapUsername", username);
        ReflectionTestUtils.setField(config, "bootstrapPassword", password);
        return config;
    }
}
