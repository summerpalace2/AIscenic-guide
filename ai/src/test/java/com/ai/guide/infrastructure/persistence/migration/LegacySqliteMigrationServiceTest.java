package com.ai.guide.infrastructure.persistence.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacySqliteMigrationServiceTest {
    @TempDir Path directory;

    @Test void migrationCanBeRetriedWithoutDuplicatingRows() {
        Path legacyPath = directory.resolve("legacy.db");
        var legacySource = new DriverManagerDataSource("jdbc:sqlite:" + legacyPath.toAbsolutePath());
        var legacy = new JdbcTemplate(legacySource);
        legacy.execute("CREATE TABLE kb_document(id TEXT PRIMARY KEY,title TEXT,category TEXT,content TEXT,tags TEXT,file_url TEXT,file_md5 TEXT,status TEXT,vector_status TEXT,chunk_count INTEGER,created_by TEXT,created_at TEXT,updated_at TEXT)");
        legacy.update("INSERT INTO kb_document VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)", "doc-1", "fixture", "culture", "body", "[]", "", "", "active", "pending", 0, "tester", "2026-01-01", "2026-01-01");
        legacy.execute("CREATE TABLE users(id TEXT PRIMARY KEY,username TEXT,password_hash TEXT,role TEXT,status TEXT,nickname TEXT,avatar TEXT,phone TEXT,interests TEXT,created_at TEXT,updated_at TEXT,password_scheme TEXT,password_version TEXT,password_parameters TEXT,rehash_required INTEGER,reset_required INTEGER)");
        legacy.execute("CREATE TABLE app_config(key TEXT PRIMARY KEY,value TEXT,updated_at TEXT)");

        var targetSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        var target = new JdbcTemplate(targetSource);
        target.execute("CREATE TABLE kb_document(id TEXT PRIMARY KEY,title TEXT,category TEXT,content TEXT,tags TEXT,file_url TEXT,file_md5 TEXT,source_url TEXT NOT NULL DEFAULT '',status TEXT,vector_status TEXT,chunk_count INTEGER,created_by TEXT,created_at TEXT,updated_at TEXT)");
        target.execute("CREATE TABLE users(id TEXT PRIMARY KEY,username TEXT,password_hash TEXT,role TEXT,status TEXT,nickname TEXT,avatar TEXT,phone TEXT,interests TEXT,created_at TEXT,updated_at TEXT,password_scheme TEXT,password_version TEXT,password_parameters TEXT,rehash_required INTEGER,reset_required INTEGER)");
        target.execute("CREATE TABLE app_config(key TEXT PRIMARY KEY,value TEXT,updated_at TEXT)");

        var service = new LegacySqliteMigrationService();
        service.migrateIfPresent(target, legacyPath.toFile());
        service.migrateIfPresent(target, legacyPath.toFile());

        assertEquals(1, target.queryForObject("SELECT COUNT(*) FROM kb_document WHERE id='doc-1'", Integer.class));
        assertEquals(1, target.queryForObject("SELECT COUNT(*) FROM app_config WHERE key='system.legacy_sqlite_migrated'", Integer.class));
        assertEquals(1, target.queryForObject("SELECT COUNT(*) FROM app_config WHERE key='system.legacy_sqlite_formal_trips_migrated'", Integer.class));
        targetSource.destroy();
    }
}
