package com.ai.guide.infrastructure.admin.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 全局应用配置仓储基础设施层单元测试套件
 */
class AppConfigRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private AppConfigRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        repository = new AppConfigRepository(jdbcTemplate);
    }

    @Test
    void testReadConfig_Success() {
        Map<String, String> res = repository.readConfig("settings_");
        assertNotNull(res);
        verify(jdbcTemplate).query(eq("SELECT key, value FROM app_config WHERE key LIKE ?"), any(RowCallbackHandler.class), eq("settings_%"));
    }

    @Test
    void testReadConfig_ExceptionGracefulFallback() {
        doThrow(new RuntimeException("DB error")).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), anyString());
        Map<String, String> res = repository.readConfig("settings_");
        assertNotNull(res);
        assertTrue(res.isEmpty());
    }

    @Test
    void testWriteConfig_UpdateThenInsert() {
        // Mock update returning 0 -> triggers insert
        when(jdbcTemplate.update(startsWith("UPDATE"), any(), any(), any())).thenReturn(0);

        repository.writeConfig("settings_", Map.of("key1", "val1"));

        verify(jdbcTemplate).update(startsWith("UPDATE"), eq("val1"), anyString(), eq("settings_key1"));
        verify(jdbcTemplate).update(startsWith("INSERT"), eq("settings_key1"), eq("val1"), anyString());
    }

    @Test
    void testWriteConfig_EmptyOrNull() {
        repository.writeConfig("settings_", null);
        repository.writeConfig("settings_", Map.of());
        verifyNoInteractions(jdbcTemplate);
    }
}
