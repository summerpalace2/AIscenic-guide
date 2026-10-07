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
        assertThrows(IllegalStateException.class, () -> repository.readConfig("settings_"));
    }

    @Test
    void testWriteConfig_UpdateThenInsert() {
        var source = new org.springframework.jdbc.datasource.SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        try {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("CREATE TABLE app_config(key TEXT PRIMARY KEY,value TEXT,updated_at TEXT)");
            var real = new AppConfigRepository(jdbc);
            real.writeConfig("settings_", Map.of("key1", "val1"));
            assertEquals("val1", real.readConfig("settings_").get("key1"));
            real.writeConfig("settings_", Map.of("key1", "val2"));
            assertEquals("val2", real.readConfig("settings_").get("key1"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM app_config", Integer.class));
        } finally { source.destroy(); }
    }

    @Test
    void testWriteConfig_EmptyOrNull() {
        repository.writeConfig("settings_", null);
        repository.writeConfig("settings_", Map.of());
        verifyNoInteractions(jdbcTemplate);
    }
}
