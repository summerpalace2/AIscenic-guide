package com.ai.guide.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeDbConfigTest {
    @Test
    void resolvesZeaburPostgresVariableNames() {
        KnowledgeDbConfig config = new KnowledgeDbConfig();
        ReflectionTestUtils.setField(config, "postgresHost", "postgresql.zeabur.internal");
        ReflectionTestUtils.setField(config, "postgresPort", "5432");
        ReflectionTestUtils.setField(config, "postgresDatabase", "scenic_guide");
        ReflectionTestUtils.setField(config, "postgresUsername", "root");
        ReflectionTestUtils.setField(config, "postgresPassword", "test-password");

        Object settings = ReflectionTestUtils.invokeMethod(config, "resolveSettings");

        assertEquals("postgresql", ReflectionTestUtils.getField(settings, "type"));
        assertEquals("jdbc:postgresql://postgresql.zeabur.internal:5432/scenic_guide",
                ReflectionTestUtils.getField(settings, "url"));
        assertEquals("root", ReflectionTestUtils.getField(settings, "username"));
        assertEquals("test-password", ReflectionTestUtils.getField(settings, "password"));
    }

    @Test
    void keepsLegacyPostgresDbAndUserVariableNames() {
        KnowledgeDbConfig config = new KnowledgeDbConfig();
        ReflectionTestUtils.setField(config, "postgresHost", "postgresql.zeabur.internal");
        ReflectionTestUtils.setField(config, "postgresPort", "5432");
        ReflectionTestUtils.setField(config, "legacyPostgresDatabase", "legacy_db");
        ReflectionTestUtils.setField(config, "postgresUser", "legacy_user");

        Object settings = ReflectionTestUtils.invokeMethod(config, "resolveSettings");

        assertEquals("jdbc:postgresql://postgresql.zeabur.internal:5432/legacy_db",
                ReflectionTestUtils.getField(settings, "url"));
        assertEquals("legacy_user", ReflectionTestUtils.getField(settings, "username"));
    }

    @Test
    void failsClearlyWhenPostgresIsSelectedWithoutConnectionDetails() {
        KnowledgeDbConfig config = new KnowledgeDbConfig();
        ReflectionTestUtils.setField(config, "configuredType", "postgresql");
        ReflectionTestUtils.setField(config, "postgresHost", "");

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(config, "resolveSettings"));
    }
}