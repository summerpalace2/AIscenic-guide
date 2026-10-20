package com.ai.guide.infrastructure.persistence.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.io.File;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Idempotent import of the legacy local SQLite database into the active store. */
@Component
public class LegacySqliteMigrationService {
    private static final Logger log = LoggerFactory.getLogger(LegacySqliteMigrationService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void migrateIfPresent(JdbcTemplate target, File legacyFile) {
        if (legacyFile == null || !legacyFile.isFile()) return;
        try {
            Integer migrated = target.queryForObject(
                    "SELECT COUNT(*) FROM app_config WHERE key = ?", Integer.class, "system.legacy_sqlite_migrated");
            if (migrated != null && migrated > 0) {
                migrateFormalTrips(target, legacyFile);
                return;
            }
        } catch (Exception e) {
            log.warn("[KnowledgeDB] 检查 SQLite 迁移标记失败: {}", e.getMessage());
        }

        JdbcTemplate legacy = source(legacyFile);
        int total = 0;
        total += migrateTable(legacy, target, "kb_document",
                List.of("id", "title", "category", "content", "tags", "file_url", "file_md5", "status", "vector_status", "chunk_count", "created_by", "created_at", "updated_at"));
        total += migrateTable(legacy, target, "users",
                List.of("id", "username", "password_hash", "role", "status", "nickname", "avatar", "phone", "interests", "created_at", "updated_at"));
        migrateLegacyPasswordMetadata(target);
        total += migrateServiceLogs(legacy, target);
        total += migrateTable(legacy, target, "analytics_report",
                List.of("id", "title", "type", "period_start", "period_end", "status", "data", "created_at"));
        total += migrateTable(legacy, target, "app_config", List.of("key", "value", "updated_at"));
        try {
            upsertConfig(target, "system.legacy_sqlite_migrated", "1");
            log.info("[KnowledgeDB] SQLite 全量迁移完成，共迁移 {} 条记录", total);
            migrateFormalTrips(target, legacyFile);
        } catch (Exception e) {
            log.warn("[KnowledgeDB] 写入迁移标记失败，下次启动将继续检查: {}", e.getMessage());
        }
    }

    private void migrateFormalTrips(JdbcTemplate target, File legacyFile) {
        String marker = "system.legacy_sqlite_formal_trips_migrated";
        try {
            Integer completed = target.queryForObject("SELECT COUNT(*) FROM app_config WHERE key = ?", Integer.class, marker);
            if (completed != null && completed > 0) return;
            JdbcTemplate legacy = source(legacyFile);
            int trips = migrateTable(legacy, target, "trip",
                    List.of("id", "owner_id", "title", "status", "current_version", "created_at", "updated_at"));
            int versions = migrateTable(legacy, target, "trip_version",
                    List.of("id", "trip_id", "version_number", "snapshot", "change_reason", "created_at", "created_by"));
            upsertConfig(target, marker, "1");
            log.info("[KnowledgeDB] 正式行程迁移完成：trip {} 条，trip_version {} 条", trips, versions);
        } catch (Exception error) {
            log.warn("[KnowledgeDB] 正式行程迁移暂未完成，下次启动会继续：{}", error.getMessage());
        }
    }

    private static JdbcTemplate source(File file) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite:" + file.getAbsolutePath().replace('\\', '/'));
        return new JdbcTemplate(dataSource);
    }

    private void migrateLegacyPasswordMetadata(JdbcTemplate target) {
        try {
            target.update("UPDATE users SET password_scheme = ?, password_version = ?, password_parameters = ?, " +
                            "rehash_required = 0, reset_required = 1 WHERE NOT (UPPER(COALESCE(password_scheme, '')) = 'BCRYPT' " +
                            "AND COALESCE(password_hash, '') LIKE '$2%') AND UPPER(COALESCE(password_scheme, '')) <> 'LEGACY_SCRYPT'",
                    "UNKNOWN", "unknown-v1", "{}");
        } catch (Exception error) {
            log.warn("[KnowledgeDB] legacy password metadata classification skipped: {}", error.getMessage());
        }
    }

    private int migrateTable(JdbcTemplate legacy, JdbcTemplate target, String table, List<String> columns) {
        String columnSql = String.join(", ", columns);
        List<Map<String, Object>> rows;
        try { rows = legacy.queryForList("SELECT " + columnSql + " FROM " + table); }
        catch (Exception e) { return 0; }
        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        String sql = "INSERT INTO " + table + " (" + columnSql + ") VALUES (" + placeholders + ") ON CONFLICT DO NOTHING";
        List<Object[]> args = rows.stream().map(row -> columns.stream().map(row::get).toArray()).toList();
        int migrated = 0;
        for (int result : target.batchUpdate(sql, args)) if (result != Statement.EXECUTE_FAILED && result != 0) migrated++;
        return migrated;
    }

    private int migrateServiceLogs(JdbcTemplate legacy, JdbcTemplate target) {
        try {
            List<String> columns = legacy.query("PRAGMA table_info(service_log)", (rs, rowNum) -> rs.getString("name"));
            if (columns.contains("user_id")) return migrateTable(legacy, target, "service_log",
                    List.of("id", "session_id", "user_id", "question", "emotion", "intent", "response_time_ms", "created_at"));
        } catch (Exception ignored) { }
        return migrateTable(legacy, target, "service_log",
                List.of("id", "session_id", "question", "emotion", "intent", "response_time_ms", "created_at"));
    }

    private void upsertConfig(JdbcTemplate target, String key, String value) {
        String now = LocalDateTime.now().format(DT_FMT);
        int updated = target.update("UPDATE app_config SET value = ?, updated_at = ? WHERE key = ?", value, now, key);
        if (updated == 0) target.update("INSERT INTO app_config (key, value, updated_at) VALUES (?, ?, ?)", key, value, now);
    }
}
