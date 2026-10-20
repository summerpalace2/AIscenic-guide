package com.ai.guide.infrastructure.persistence.migration;

import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import javax.sql.DataSource;
import java.io.File;
import java.io.InputStream;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@org.springframework.stereotype.Component
public class KnowledgeSchemaInitializer {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KnowledgeSchemaInitializer.class);
    private static final Object[] MIGRATION_LOCKS = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private static final java.time.format.DateTimeFormatter DT_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void initialize(JdbcTemplate jdbcTemplate, boolean sqlite) {
        if (sqlite) {
            createSchema(jdbcTemplate, true);
            return;
        }
        DataSource source = java.util.Objects.requireNonNull(jdbcTemplate.getDataSource());
        // The first legacy table and migration ledger also need cross-instance protection.
        // A transaction-scoped lock must use the same connection as all subsequent DDL.
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
            jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(471932761)");
            createSchema(jdbcTemplate, false);
        });
    }
    private void createSchema(JdbcTemplate jdbcTemplate, boolean sqlite) {
        String timeDefault = sqlite ? " DEFAULT (datetime('now','localtime'))" : " DEFAULT ''";
        String idType = "VARCHAR(128)";
        String varchar = "VARCHAR(255)";
        String timeType = "VARCHAR(32)";
        // SQLite 接受 0/1 作为布尔默认值，PostgreSQL 仅接受 TRUE/FALSE；
        // 首次连接云端数据库时必须按方言生成，避免 schema 初始化中断。
        String booleanFalse = sqlite ? "0" : "FALSE";

        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS kb_document (" +
                "id " + idType + " PRIMARY KEY," +
                "title " + varchar + " NOT NULL," +
                "category " + varchar + " NOT NULL DEFAULT ''," +
                "content TEXT DEFAULT ''," +
                "tags TEXT DEFAULT '[]'," +
                "file_url TEXT DEFAULT ''," +
                "file_md5 VARCHAR(64) DEFAULT ''," +
                "source_name " + varchar + " DEFAULT ''," +
                "source_url TEXT DEFAULT ''," +
                "index_request_mode VARCHAR(16) NOT NULL DEFAULT ''," +
                "index_attempts INTEGER NOT NULL DEFAULT 0," +
                "index_next_attempt_at BIGINT NOT NULL DEFAULT 0," +
                "index_lease_token VARCHAR(64) NOT NULL DEFAULT ''," +
                "index_lease_until BIGINT NOT NULL DEFAULT 0," +
                "status " + varchar + " NOT NULL DEFAULT 'active'," +
                "vector_status " + varchar + " NOT NULL DEFAULT 'pending'," +
                "chunk_count INTEGER DEFAULT 0," +
                "created_by " + varchar + " DEFAULT ''," +
                "created_at " + timeType + " NOT NULL" + timeDefault + "," +
                "updated_at " + timeType + " NOT NULL" + timeDefault +
                ")");
        ensureColumn(jdbcTemplate, "kb_document", "source_name " + varchar + " DEFAULT ''");
        ensureColumn(jdbcTemplate, "kb_document", "source_url TEXT DEFAULT ''");
        ensureColumn(jdbcTemplate, "kb_document", "index_request_mode VARCHAR(16) NOT NULL DEFAULT ''");
        ensureColumn(jdbcTemplate, "kb_document", "index_attempts INTEGER NOT NULL DEFAULT 0");
        ensureColumn(jdbcTemplate, "kb_document", "index_next_attempt_at BIGINT NOT NULL DEFAULT 0");
        ensureColumn(jdbcTemplate, "kb_document", "index_lease_token VARCHAR(64) NOT NULL DEFAULT ''");
        ensureColumn(jdbcTemplate, "kb_document", "index_lease_until BIGINT NOT NULL DEFAULT 0");
        ensureColumn(jdbcTemplate, "kb_document", "index_revision INTEGER NOT NULL DEFAULT 0");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_kb_document_index_due ON kb_document(index_request_mode, index_next_attempt_at, index_lease_until)");
        log.info("[KnowledgeDB] kb_document 表已就绪");

        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS users (" +
                "id " + idType + " PRIMARY KEY," +
                "username " + varchar + " UNIQUE NOT NULL," +
                "password_hash TEXT NOT NULL," +
                "password_salt TEXT NOT NULL DEFAULT ''," +
                "password_scheme VARCHAR(32) NOT NULL DEFAULT 'BCRYPT'," +
                "password_version VARCHAR(32) NOT NULL DEFAULT '1'," +
                "password_parameters TEXT DEFAULT '{\"cost\":10}'," +
                "rehash_required INTEGER NOT NULL DEFAULT 0," +
                "reset_required INTEGER NOT NULL DEFAULT 0," +
                "role " + varchar + " NOT NULL DEFAULT 'USER'," +
                "status " + varchar + " NOT NULL DEFAULT 'active'," +
                "nickname " + varchar + " DEFAULT ''," +
                "avatar TEXT DEFAULT ''," +
                "phone " + varchar + " DEFAULT ''," +
                "interests TEXT DEFAULT '[]'," +
                "created_at " + timeType + " NOT NULL" + timeDefault + "," +
                "updated_at " + timeType + " NOT NULL" + timeDefault +
                ")");
        ensureColumn(jdbcTemplate, "users", "password_salt TEXT NOT NULL DEFAULT ''");
        ensureColumn(jdbcTemplate, "users", "password_scheme VARCHAR(32) NOT NULL DEFAULT 'BCRYPT'");
        ensureColumn(jdbcTemplate, "users", "password_version VARCHAR(32) NOT NULL DEFAULT '1'");
        ensureColumn(jdbcTemplate, "users", "password_parameters TEXT DEFAULT '{\"cost\":10}'");
        ensureColumn(jdbcTemplate, "users", "rehash_required INTEGER NOT NULL DEFAULT 0");
        ensureColumn(jdbcTemplate, "users", "reset_required INTEGER NOT NULL DEFAULT 0");
        log.info("[KnowledgeDB] users 表已就绪");

        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS service_log (" +
                "id " + idType + " PRIMARY KEY," +
                "session_id " + varchar + " NOT NULL DEFAULT ''," +
                "user_id " + varchar + " DEFAULT ''," +
                "question TEXT DEFAULT ''," +
                "emotion " + varchar + " DEFAULT ''," +
                "intent " + varchar + " DEFAULT ''," +
                "response_time_ms INTEGER DEFAULT 0," +
                "created_at " + timeType + " NOT NULL" + timeDefault +
                ")");
        log.info("[KnowledgeDB] service_log 表已就绪");

        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS analytics_report (" +
                "id " + idType + " PRIMARY KEY," +
                "title " + varchar + " NOT NULL," +
                "type " + varchar + " NOT NULL DEFAULT 'daily'," +
                "period_start " + timeType + " NOT NULL," +
                "period_end " + timeType + " NOT NULL," +
                "status " + varchar + " NOT NULL DEFAULT 'completed'," +
                "data TEXT DEFAULT '{}'," +
                "created_at " + timeType + " NOT NULL" + timeDefault +
                ")");
        log.info("[KnowledgeDB] analytics_report 表已就绪");

        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS app_config (" +
                "key " + varchar + " PRIMARY KEY," +
                "value TEXT DEFAULT ''," +
                "updated_at " + timeType + " NOT NULL" + timeDefault +
                ")");
        log.info("[KnowledgeDB] app_config 表已就绪");

        // TripPlan 只保存用户提交的结构化 JSON，不读取或重建旧知识库事实。
        // payload 保留 dataStatus/FactStatus/Citation 等字段，便于后续 Data Pipeline/API Binding 替换。
        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS trip_plan (" +
                "id " + idType + " PRIMARY KEY," +
                "user_id " + varchar + " NOT NULL," +
                "payload TEXT NOT NULL DEFAULT '{}'," +
                "created_at BIGINT NOT NULL," +
                "updated_at BIGINT NOT NULL" +
                ")");
        log.info("[KnowledgeDB] trip_plan 表已就绪");

        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS attraction (" +
                "id " + idType + " PRIMARY KEY," +
                "name " + varchar + " NOT NULL," +
                "display_name " + varchar + " NOT NULL DEFAULT ''," +
                "city " + varchar + " NOT NULL DEFAULT ''," +
                "district " + varchar + " NOT NULL DEFAULT ''," +
                "category " + varchar + " NOT NULL DEFAULT ''," +
                "tags TEXT DEFAULT '[]'," +
                "icon VARCHAR(64) DEFAULT ''," +
                "tone VARCHAR(64) DEFAULT ''," +
                "location VARCHAR(64) DEFAULT ''," +
                "summary TEXT DEFAULT ''," +
                "walk TEXT DEFAULT ''," +
                "duration " + varchar + " DEFAULT ''," +
                "indoor BOOLEAN DEFAULT " + booleanFalse + "," +
                "walk_difficulty " + varchar + " DEFAULT ''," +
                "ticket TEXT DEFAULT ''," +
                "best_time " + varchar + " DEFAULT ''," +
                "amap_query TEXT DEFAULT '{}'," +
                "intro TEXT DEFAULT ''," +
                "fit TEXT DEFAULT ''," +
                "accessibility " + varchar + " DEFAULT ''," +
                "environment " + varchar + " DEFAULT ''," +
                "recommended_visit_minutes INTEGER," +
                "companion_tags TEXT DEFAULT '[]'," +
                "feature_tags TEXT DEFAULT '[]'," +
                "photo_url TEXT DEFAULT ''" +
                ")");
        ensureColumn(jdbcTemplate, "attraction", "accessibility " + varchar + " DEFAULT ''");
        ensureColumn(jdbcTemplate, "attraction", "city " + varchar + " NOT NULL DEFAULT ''");
        ensureColumn(jdbcTemplate, "attraction", "environment " + varchar + " DEFAULT ''");
        ensureColumn(jdbcTemplate, "attraction", "recommended_visit_minutes INTEGER");
        ensureColumn(jdbcTemplate, "attraction", "companion_tags TEXT DEFAULT '[]'");
        ensureColumn(jdbcTemplate, "attraction", "feature_tags TEXT DEFAULT '[]'");
        ensureColumn(jdbcTemplate, "attraction", "photo_url TEXT DEFAULT ''");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_attraction_city_category_district ON attraction(city, category, district)");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS attraction_catalog_entry (id VARCHAR(128) PRIMARY KEY, city VARCHAR(255) NOT NULL, source_url TEXT NOT NULL, verified_at VARCHAR(32) NOT NULL, verified_fields TEXT NOT NULL DEFAULT '[]')");
        ensureColumn(jdbcTemplate, "attraction_catalog_entry", "verified_fields TEXT NOT NULL DEFAULT '[]'");
        ensureColumn(jdbcTemplate, "attraction_catalog_entry", "aliases TEXT NOT NULL DEFAULT '[]'");
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS attraction_catalog_revision (id INTEGER PRIMARY KEY, revision BIGINT NOT NULL, fingerprint VARCHAR(64) NOT NULL, release_version BIGINT NOT NULL DEFAULT 0, release_fingerprint VARCHAR(64) NOT NULL DEFAULT '')");
        ensureColumn(jdbcTemplate, "attraction_catalog_revision", "release_version BIGINT NOT NULL DEFAULT 0");
        ensureColumn(jdbcTemplate, "attraction_catalog_revision", "release_fingerprint VARCHAR(64) NOT NULL DEFAULT ''");
        jdbcTemplate.update("INSERT INTO attraction_catalog_revision (id,revision,fingerprint) VALUES (1,0,'') ON CONFLICT(id) DO NOTHING");
        log.info("[KnowledgeDB] attraction 表已就绪");

        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS dining_venue (" +
                "id " + idType + " PRIMARY KEY," +
                "name " + varchar + " NOT NULL," +
                "district " + varchar + " NOT NULL DEFAULT ''," +
                "category " + varchar + " NOT NULL DEFAULT ''," +
                "meal_types " + varchar + " NOT NULL DEFAULT '[]'," +
                "location VARCHAR(64) DEFAULT ''," +
                "specialty_dish " + varchar + " DEFAULT ''," +
                "average_cost " + varchar + " DEFAULT ''," +
                "duration " + varchar + " DEFAULT ''," +
                "distance_desc " + varchar + " DEFAULT ''," +
                "summary TEXT DEFAULT ''," +
                "recommendation_reason TEXT DEFAULT ''," +
                "spiciness_level VARCHAR(32) DEFAULT 'MODERATE'," +
                "elder_friendly BOOLEAN DEFAULT " + (sqlite ? "1" : "TRUE") + "," +
                "tone VARCHAR(32) DEFAULT 'gold'," +
                "tags TEXT DEFAULT '[]'" +
                ")");
        log.info("[KnowledgeDB] dining_venue 表已就绪");

        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS planner_session (" +
                "session_id " + idType + " PRIMARY KEY," +
                "owner_type " + varchar + " NOT NULL," +
                "owner_id " + varchar + " NOT NULL," +
                "access_token_hash VARCHAR(128) DEFAULT ''," +
                "idempotency_key VARCHAR(128)," +
                "request_fingerprint VARCHAR(64) DEFAULT ''," +
                "prompt TEXT DEFAULT ''," +
                "constraints_json TEXT NOT NULL DEFAULT '{}'," +
                "applied_preferences_json TEXT NOT NULL DEFAULT '[]'," +
                "current_trip_json TEXT NOT NULL DEFAULT '{}'," +
                "current_version INTEGER NOT NULL DEFAULT 1," +
                "sync_revision INTEGER NOT NULL DEFAULT 1," +
                "status " + varchar + " NOT NULL DEFAULT 'ACTIVE'," +
                "created_at BIGINT NOT NULL," +
                "updated_at BIGINT NOT NULL" +
                ")");
        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS planner_plan_revision (" +
                "session_id " + idType + " NOT NULL," +
                "version INTEGER NOT NULL," +
                "mutation_type " + varchar + " NOT NULL," +
                "label " + varchar + " NOT NULL," +
                "reason TEXT DEFAULT ''," +
                "changed_segments_json TEXT NOT NULL DEFAULT '[]'," +
                "trip_json TEXT NOT NULL," +
                "created_at BIGINT NOT NULL," +
                "PRIMARY KEY (session_id, version)" +
                ")");
        jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_planner_session_owner ON planner_session (owner_type, owner_id, updated_at)");
        jdbcTemplate.update("CREATE UNIQUE INDEX IF NOT EXISTS uq_planner_session_idempotency ON planner_session (owner_type, owner_id, idempotency_key)");
        jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_planner_revision_session ON planner_plan_revision (session_id, version)");
        log.info("[KnowledgeDB] planner session/revision 表已就绪");

        applyVersionedMigrations(jdbcTemplate);
    }

    /**
     * Applies additive business-schema migrations exactly once per version.
     * The existing CREATE TABLE statements remain for legacy tables; formal Trip
     * storage is deliberately owned by this versioned path so future changes do
     * not silently depend on CREATE TABLE IF NOT EXISTS behavior.
     */
    private void applyVersionedMigrations(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS schema_migration (" +
                "version INTEGER PRIMARY KEY," +
                "description VARCHAR(255) NOT NULL," +
                "applied_at BIGINT NOT NULL" +
                ")");

        applyMigration(jdbcTemplate, 1, "formal Trip and TripVersion storage", () -> {
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS trip (" +
                    "id VARCHAR(128) PRIMARY KEY," +
                    "owner_id VARCHAR(255) NOT NULL," +
                    "title VARCHAR(255) NOT NULL DEFAULT ''," +
                    "status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'," +
                    "current_version INTEGER NOT NULL," +
                    "created_at BIGINT NOT NULL," +
                    "updated_at BIGINT NOT NULL" +
                    ")");
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS trip_version (" +
                    "id VARCHAR(128) PRIMARY KEY," +
                    "trip_id VARCHAR(128) NOT NULL," +
                    "version_number INTEGER NOT NULL," +
                    "snapshot TEXT NOT NULL," +
                    "change_reason VARCHAR(255) NOT NULL DEFAULT ''," +
                    "created_at BIGINT NOT NULL," +
                    "created_by VARCHAR(255) NOT NULL DEFAULT ''," +
                    "UNIQUE (trip_id, version_number)" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_trip_owner_updated ON trip (owner_id, updated_at)");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_trip_version_trip ON trip_version (trip_id, version_number)");
            verifyIndex(jdbcTemplate, "trip", "idx_trip_owner_updated", List.of("owner_id", "updated_at"));
            verifyIndex(jdbcTemplate, "trip_version", "idx_trip_version_trip", List.of("trip_id", "version_number"));
        });

        applyMigration(jdbcTemplate, 2, "Trip mutation idempotency records", () -> {
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS trip_idempotency (" +
                    "owner_id VARCHAR(255) NOT NULL," +
                    "operation VARCHAR(64) NOT NULL," +
                    "idempotency_key VARCHAR(255) NOT NULL," +
                    "request_fingerprint VARCHAR(128) NOT NULL," +
                    "trip_id VARCHAR(128) NOT NULL," +
                    "version_number INTEGER NOT NULL," +
                    "response_json TEXT NOT NULL," +
                    "created_at BIGINT NOT NULL," +
                    "PRIMARY KEY (owner_id, operation, idempotency_key)" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_trip_idempotency_trip ON trip_idempotency (trip_id)");
            verifyIndex(jdbcTemplate, "trip_idempotency", "idx_trip_idempotency_trip", List.of("trip_id"));
        });

        applyMigration(jdbcTemplate, 3, "Phase 5B legacy identity and Trip migration ledger", () -> {
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS legacy_migration_run (" +
                    "run_id VARCHAR(128) PRIMARY KEY," +
                    "source_system VARCHAR(64) NOT NULL," +
                    "source_checksum VARCHAR(64) NOT NULL," +
                    "mode VARCHAR(16) NOT NULL," +
                    "status VARCHAR(32) NOT NULL," +
                    "summary_json TEXT NOT NULL DEFAULT '{}'," +
                    "created_at BIGINT NOT NULL," +
                    "updated_at BIGINT NOT NULL" +
                    ")");
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS legacy_user_map (" +
                    "source_system VARCHAR(64) NOT NULL," +
                    "legacy_user_id VARCHAR(255) NOT NULL," +
                    "java_user_id VARCHAR(128) NOT NULL," +
                    "source_record_checksum VARCHAR(64) NOT NULL," +
                    "mapped_role VARCHAR(32) NOT NULL," +
                    "credential_disposition VARCHAR(32) NOT NULL," +
                    "resolution_type VARCHAR(64) NOT NULL DEFAULT 'AUTOMATIC'," +
                    "run_id VARCHAR(128) NOT NULL," +
                    "created_at BIGINT NOT NULL," +
                    "updated_at BIGINT NOT NULL," +
                    "PRIMARY KEY (source_system, legacy_user_id)," +
                    "UNIQUE (source_system, java_user_id)" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_legacy_user_map_java ON legacy_user_map (java_user_id)");
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS legacy_trip_map (" +
                    "source_system VARCHAR(64) NOT NULL," +
                    "legacy_owner_id VARCHAR(255) NOT NULL," +
                    "legacy_trip_id VARCHAR(255) NOT NULL," +
                    "java_owner_id VARCHAR(128) NOT NULL," +
                    "formal_trip_id VARCHAR(128) NOT NULL," +
                    "source_record_checksum VARCHAR(64) NOT NULL," +
                    "run_id VARCHAR(128) NOT NULL," +
                    "status VARCHAR(32) NOT NULL," +
                    "created_at BIGINT NOT NULL," +
                    "updated_at BIGINT NOT NULL," +
                    "PRIMARY KEY (source_system, legacy_owner_id, legacy_trip_id)," +
                    "UNIQUE (source_system, formal_trip_id)" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_legacy_trip_map_owner ON legacy_trip_map (java_owner_id)");
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS legacy_migration_failure (" +
                    "id VARCHAR(128) PRIMARY KEY," +
                    "run_id VARCHAR(128) NOT NULL," +
                    "entity_type VARCHAR(32) NOT NULL," +
                    "source_key VARCHAR(255) NOT NULL," +
                    "source_record_checksum VARCHAR(64) NOT NULL DEFAULT ''," +
                    "failure_code VARCHAR(64) NOT NULL," +
                    "sanitized_message VARCHAR(512) NOT NULL DEFAULT ''," +
                    "retryable INTEGER NOT NULL DEFAULT 0," +
                    "created_at BIGINT NOT NULL" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_legacy_failure_run ON legacy_migration_failure (run_id, entity_type)");
        });

        applyMigration(jdbcTemplate, 4, "Phase 5C typed Java Preferences authority", () -> {
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS user_preferences (" +
                    "user_id VARCHAR(128) PRIMARY KEY," +
                    "schema_version INTEGER NOT NULL DEFAULT 1," +
                    "revision BIGINT NOT NULL DEFAULT 1," +
                    "interests_json TEXT NOT NULL DEFAULT '[]'," +
                    "walking_tolerance VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED'," +
                    "budget_json TEXT NOT NULL DEFAULT '{}'," +
                    "companions VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED'," +
                    "transport_preference VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED'," +
                    "diet_preference VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED'," +
                    "stay_area VARCHAR(255) NOT NULL DEFAULT ''," +
                    "legacy_metadata_json TEXT NOT NULL DEFAULT '{}'," +
                    "created_at BIGINT NOT NULL," +
                    "updated_at BIGINT NOT NULL" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_user_preferences_updated " +
                    "ON user_preferences (updated_at, user_id)");
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS user_preference_audit (" +
                    "event_id VARCHAR(128) PRIMARY KEY," +
                    "user_id VARCHAR(128) NOT NULL," +
                    "revision BIGINT NOT NULL," +
                    "operation VARCHAR(32) NOT NULL," +
                    "actor_type VARCHAR(32) NOT NULL," +
                    "actor_id VARCHAR(128) NOT NULL," +
                    "scope VARCHAR(64) NOT NULL," +
                    "source VARCHAR(64) NOT NULL," +
                    "device VARCHAR(128) NOT NULL DEFAULT ''," +
                    "session_id VARCHAR(128) NOT NULL DEFAULT ''," +
                    "planner_session_id VARCHAR(128) NOT NULL DEFAULT ''," +
                    "schema_version INTEGER NOT NULL," +
                    "normalization_version VARCHAR(64) NOT NULL," +
                    "decision VARCHAR(32) NOT NULL DEFAULT ''," +
                    "before_json TEXT NOT NULL DEFAULT '{}'," +
                    "after_json TEXT NOT NULL DEFAULT '{}'," +
                    "created_at BIGINT NOT NULL" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_user_preference_audit_user " +
                    "ON user_preference_audit (user_id, revision)");
        });

        applyMigration(jdbcTemplate, 5, "Phase 5C-2 preference migration ledger and quarantine", () -> {
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS preference_migration_run (" +
                    "run_id VARCHAR(128) PRIMARY KEY," +
                    "source_system VARCHAR(64) NOT NULL," +
                    "source_snapshot_sha256 VARCHAR(64) NOT NULL," +
                    "mode VARCHAR(16) NOT NULL," +
                    "status VARCHAR(32) NOT NULL," +
                    "summary_json TEXT NOT NULL DEFAULT '{}'," +
                    "created_at BIGINT NOT NULL," +
                    "updated_at BIGINT NOT NULL" +
                    ")");
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS preference_migration_ledger (" +
                    "source_system VARCHAR(64) NOT NULL," +
                    "legacy_user_id VARCHAR(255) NOT NULL," +
                    "java_user_id VARCHAR(128) NOT NULL DEFAULT ''," +
                    "source_snapshot_sha256 VARCHAR(64) NOT NULL," +
                    "active_payload_checksum VARCHAR(64) NOT NULL," +
                    "history_checksum VARCHAR(64) NOT NULL," +
                    "normalization_version VARCHAR(64) NOT NULL," +
                    "status VARCHAR(64) NOT NULL," +
                    "run_id VARCHAR(128) NOT NULL," +
                    "created_at BIGINT NOT NULL," +
                    "updated_at BIGINT NOT NULL," +
                    "PRIMARY KEY (source_system, legacy_user_id, source_snapshot_sha256)" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_preference_migration_ledger_latest " +
                    "ON preference_migration_ledger (source_system, legacy_user_id, updated_at)");
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS legacy_preference_quarantine (" +
                    "quarantine_id VARCHAR(128) PRIMARY KEY," +
                    "source_system VARCHAR(64) NOT NULL," +
                    "legacy_user_id VARCHAR(255) NOT NULL," +
                    "java_user_id VARCHAR(128) NOT NULL DEFAULT ''," +
                    "source_snapshot_sha256 VARCHAR(64) NOT NULL," +
                    "source_ordinal INTEGER NOT NULL," +
                    "value_checksum VARCHAR(64) NOT NULL," +
                    "legacy_value VARCHAR(255) NOT NULL DEFAULT ''," +
                    "classification VARCHAR(32) NOT NULL," +
                    "run_id VARCHAR(128) NOT NULL," +
                    "created_at BIGINT NOT NULL" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_preference_quarantine_owner " +
                    "ON legacy_preference_quarantine (source_system, legacy_user_id)");
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS preference_migration_failure (" +
                    "id VARCHAR(128) PRIMARY KEY," +
                    "run_id VARCHAR(128) NOT NULL," +
                    "source_system VARCHAR(64) NOT NULL," +
                    "legacy_user_id VARCHAR(255) NOT NULL," +
                    "failure_code VARCHAR(64) NOT NULL," +
                    "sanitized_message VARCHAR(512) NOT NULL DEFAULT ''," +
                    "created_at BIGINT NOT NULL" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_preference_migration_failure_run " +
                    "ON preference_migration_failure (run_id)");
        });

        applyMigration(jdbcTemplate, 6, "Travel memory records", () -> {
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS travel_memory (" +
                    "id VARCHAR(128) PRIMARY KEY," +
                    "user_id VARCHAR(128) NOT NULL," +
                    "category VARCHAR(32) NOT NULL," +
                    "content VARCHAR(512) NOT NULL," +
                    "source_type VARCHAR(32) NOT NULL," +
                    "source_ref VARCHAR(128) NOT NULL DEFAULT ''," +
                    "status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED'," +
                    "confidence REAL NOT NULL DEFAULT 1.0," +
                    "created_at BIGINT NOT NULL," +
                    "updated_at BIGINT NOT NULL" +
                    ")");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_travel_memory_user ON travel_memory (user_id, status, updated_at DESC)");
        });

        applyMigration(jdbcTemplate, 7, "Travel memory review queue", () -> {
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS travel_memory_observation (" +
                    "id VARCHAR(128) PRIMARY KEY, user_id VARCHAR(128) NOT NULL, content VARCHAR(512) NOT NULL, " +
                    "source_session VARCHAR(128) NOT NULL DEFAULT '', status VARCHAR(32) NOT NULL DEFAULT 'PENDING', created_at BIGINT NOT NULL)");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_travel_memory_observation_review ON travel_memory_observation (user_id, status, created_at)");
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS travel_memory_candidate (" +
                    "id VARCHAR(128) PRIMARY KEY, user_id VARCHAR(128) NOT NULL, category VARCHAR(32) NOT NULL, " +
                    "content VARCHAR(512) NOT NULL, source_ref VARCHAR(128) NOT NULL DEFAULT '', status VARCHAR(32) NOT NULL DEFAULT 'PENDING', " +
                    "confidence REAL NOT NULL DEFAULT 0.0, created_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_travel_memory_candidate_user ON travel_memory_candidate (user_id, status, updated_at DESC)");
        });
        applyMigration(jdbcTemplate, 8, "Planner claim replay mapping", () -> {
            ensureColumn(jdbcTemplate, "planner_session", "claimed_idempotency_key VARCHAR(128)");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_planner_session_claimed_key ON planner_session (owner_type, owner_id, claimed_idempotency_key)");
            verifyIndex(jdbcTemplate, "planner_session", "idx_planner_session_claimed_key", List.of("owner_type", "owner_id", "claimed_idempotency_key"));
        });
        applyMigration(jdbcTemplate, 9, "Explicit knowledge applicability", () -> {
            ensureColumn(jdbcTemplate, "kb_document", "scope VARCHAR(32) NOT NULL DEFAULT 'UNSPECIFIED'");
            ensureColumn(jdbcTemplate, "kb_document", "city VARCHAR(128) NOT NULL DEFAULT ''");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_kb_document_scope_city ON kb_document (scope, city, status)");
            verifyIndex(jdbcTemplate, "kb_document", "idx_kb_document_scope_city", List.of("scope", "city", "status"));
        });
        applyMigration(jdbcTemplate, 10, "Shared planner continuation and apply receipts", () -> {
            ensureColumn(jdbcTemplate,"planner_session","continuation_state_json TEXT NOT NULL DEFAULT '{}'");
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS planner_apply_receipt ("
                    + "session_id VARCHAR(128) NOT NULL,idempotency_key VARCHAR(255) NOT NULL,request_fingerprint TEXT NOT NULL,"
                    + "http_status INTEGER NOT NULL,response_json TEXT NOT NULL,created_at BIGINT NOT NULL,"
                    + "PRIMARY KEY(session_id,idempotency_key))");
        });
        applyMigration(jdbcTemplate, 11, "Knowledge source visibility lookup", () -> {
            ensureColumn(jdbcTemplate, "kb_document", "source_name VARCHAR(255) DEFAULT ''");
            jdbcTemplate.update("CREATE INDEX IF NOT EXISTS idx_kb_document_source_name ON kb_document (source_name)");
            verifyIndex(jdbcTemplate, "kb_document", "idx_kb_document_source_name", List.of("source_name"));
            jdbcTemplate.update("CREATE TABLE IF NOT EXISTS kb_backfill_source_lock (source_name VARCHAR(255) PRIMARY KEY)");
        });
    }

    private void applyMigration(JdbcTemplate jdbcTemplate, int version, String description,
                                Runnable migration) {
        DataSource source = java.util.Objects.requireNonNull(jdbcTemplate.getDataSource());
        String database = jdbcTemplate.execute((ConnectionCallback<String>) c -> c.getMetaData().getURL());
        Object lock = MIGRATION_LOCKS[Math.floorMod(database.hashCode(), MIGRATION_LOCKS.length)];
        synchronized (lock) {
            try {
                new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                    // PostgreSQL also serializes separate application instances within this transaction.
                    if (database.startsWith("jdbc:postgresql:"))
                        jdbcTemplate.queryForList("SELECT pg_advisory_xact_lock(471932761)");
                    Integer applied = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM schema_migration WHERE version = ?", Integer.class, version);
                    if (applied != null && applied > 0) return;
                    migration.run();
                    jdbcTemplate.update("INSERT INTO schema_migration (version, description, applied_at) VALUES (?, ?, ?)",
                            version, description, System.currentTimeMillis());
                });
            } catch (Exception error) {
                throw new IllegalStateException("无法应用 schema migration " + version + ": " + description, error);
            }
        }
    }

    private void verifyIndex(JdbcTemplate jdbc, String table, String index, List<String> columns) {
        List<String> actual = jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            var ordered = new java.util.TreeMap<Integer, String>();
            var metadata = connection.getMetaData();
            String schema = metadata.getURL().startsWith("jdbc:postgresql:") ? connection.getSchema() : null;
            try (var rows = metadata.getIndexInfo(connection.getCatalog(), schema, table, false, false)) {
                while (rows.next()) if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) {
                    String column = rows.getString("COLUMN_NAME");
                    if (column != null) ordered.put(rows.getInt("ORDINAL_POSITION"), column.toLowerCase(Locale.ROOT));
                }
            }
            return List.copyOf(ordered.values());
        });
        if (!columns.equals(actual)) throw new IllegalStateException("迁移索引结构不匹配：" + index);
    }

    /** Add a backward-compatible column when upgrading an existing deployment. */
    private void ensureColumn(JdbcTemplate jdbcTemplate, String tableName, String columnDefinition) {
        String columnName = columnDefinition.strip().split("\\s+", 2)[0];
        if (columnExists(jdbcTemplate, tableName, columnName)) return;
        try {
            jdbcTemplate.update("ALTER TABLE " + tableName + " ADD COLUMN " + columnDefinition);
            log.info("[KnowledgeDB] 已补齐字段: {}.{}", tableName, columnName);
        } catch (Exception error) {
            String database = jdbcTemplate.execute((ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
            // PostgreSQL aborts the current transaction after failed DDL; a metadata
            // recheck cannot recover it. Let the outer transaction roll back instead.
            if (database != null && database.startsWith("jdbc:postgresql:"))
                throw new IllegalStateException("无法补齐数据库字段 " + tableName + "." + columnName, error);
            // A concurrent initializer may have added the same column after our metadata check.
            // Ignore only that verified outcome; propagate permission, locking and dialect errors.
            if (columnExists(jdbcTemplate, tableName, columnName)) return;
            throw new IllegalStateException("无法补齐数据库字段 " + tableName + "." + columnName, error);
        }
    }

    private boolean columnExists(JdbcTemplate jdbcTemplate, String tableName, String columnName) {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            var metadata = connection.getMetaData();
            String schema = metadata.getURL().startsWith("jdbc:postgresql:") ? connection.getSchema() : null;
            try (var columns = metadata.getColumns(connection.getCatalog(), schema, null, null)) {
                while (columns.next()) {
                    // Schema patterns may contain JDBC wildcards; compare the actual schema too.
                    if ((schema == null || schema.equals(columns.getString("TABLE_SCHEM")))
                            && tableName.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                            && columnName.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
                }
            }
            return false;
        }));
    }

}
