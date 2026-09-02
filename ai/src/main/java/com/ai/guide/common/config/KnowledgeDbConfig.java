package com.ai.guide.common.config;

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

/**
 * KnowledgeDbConfig.java
 *
 * 核心职责：创建业务元数据数据库连接，并初始化知识库、用户、统计和系统配置表。
 * 主要导出：knowledgeDataSource、knowledgeJdbcTemplate。
 *
 * 本地开发默认使用 SQLite；生产环境设置 KNOWLEDGE_DB_TYPE=postgresql 和
 * KNOWLEDGE_DB_URL 后，所有业务元数据都会写入 PostgreSQL，避免 Pod 重建丢失。
 */
@Configuration
public class KnowledgeDbConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDbConfig.class);
    private static final String SQLITE_URL = "jdbc:sqlite:data/knowledge.db";
    private static final String SQLITE_DIR = "data";
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Value("${knowledge.database.type:sqlite}")
    private String configuredType;

    @Value("${knowledge.database.url:}")
    private String configuredUrl;

    @Value("${knowledge.database.username:}")
    private String configuredUsername;

    @Value("${knowledge.database.password:}")
    private String configuredPassword;

    @Value("${DATABASE_URL:}")
    private String databaseUrl;

    @Value("${POSTGRES_URL:}")
    private String postgresUrl;

    @Value("${POSTGRES_CONNECTION_STRING:}")
    private String postgresConnectionString;

    @Value("${POSTGRES_JDBC_URL:}")
    private String postgresJdbcUrl;

    /** Zeabur PostgreSQL 服务常见的连接串变量名。 */
    @Value("${POSTGRES_URI:}")
    private String postgresUri;

    @Value("${POSTGRES_HOST:}")
    private String postgresHost;

    @Value("${POSTGRES_PORT:5432}")
    private String postgresPort;

    @Value("${POSTGRES_DB:}")
    private String postgresDatabase;

    @Value("${POSTGRES_USER:}")
    private String postgresUser;

    @Value("${POSTGRES_PASSWORD:}")
    private String postgresPassword;

    private DatabaseSettings databaseSettings;

    /** 创建本地 SQLite 或生产 PostgreSQL 数据源。 */
    @Bean
    public DataSource knowledgeDataSource() {
        databaseSettings = resolveSettings();

        if (databaseSettings.sqlite()) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName(databaseSettings.driverClass());
            dataSource.setUrl(databaseSettings.url());
            File dir = new File(SQLITE_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建 SQLite 数据目录: " + dir.getAbsolutePath());
            }
            log.warn("[KnowledgeDB] 使用本地 SQLite；生产环境请设置 KNOWLEDGE_DB_URL");
            return dataSource;
        } else {
            // 直连模式在此云端会在 TLS close 阶段阻塞启动；保留小连接池，并将每个
            // HTTP 请求的短暂网络故障交给上层幂等重试处理，避免陈旧连接放大为用户失败。
            HikariConfig pool = new HikariConfig();
            pool.setPoolName("knowledge-postgres-pool");
            pool.setDriverClassName(databaseSettings.driverClass());
            pool.setJdbcUrl(databaseSettings.url());
            pool.setUsername(databaseSettings.username());
            pool.setPassword(databaseSettings.password());
            pool.setMaximumPoolSize(4);
            pool.setMinimumIdle(0);
            pool.setConnectionTimeout(15_000);
            pool.setValidationTimeout(5_000);
            pool.setMaxLifetime(45_000);
            pool.setIdleTimeout(30_000);
            pool.setKeepaliveTime(30_000);
            pool.setInitializationFailTimeout(20_000);
            pool.addDataSourceProperty("tcpKeepAlive", "true");
            pool.addDataSourceProperty("connectTimeout", "10");
            pool.addDataSourceProperty("socketTimeout", "20");
            pool.setConnectionTestQuery("SELECT 1");
            log.info("[KnowledgeDB] 使用持久化数据库: type={}, url={}", databaseSettings.type(), databaseSettings.safeUrl());
            return new HikariDataSource(pool);
        }
    }

    /** 初始化业务表，并在切换 PostgreSQL 时尝试导入本地 SQLite 数据。 */
    @Bean
    public JdbcTemplate knowledgeJdbcTemplate(DataSource knowledgeDataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(knowledgeDataSource);
        createSchema(jdbcTemplate, databaseSettings.sqlite());
        if (!databaseSettings.sqlite()) {
            migrateLegacySqlite(jdbcTemplate);
        }
        return jdbcTemplate;
    }

    /** 创建知识库、用户、统计、日报和配置表，字段采用 PostgreSQL/SQLite 兼容类型。 */
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
                "status " + varchar + " NOT NULL DEFAULT 'active'," +
                "vector_status " + varchar + " NOT NULL DEFAULT 'pending'," +
                "chunk_count INTEGER DEFAULT 0," +
                "created_by " + varchar + " DEFAULT ''," +
                "created_at " + timeType + " NOT NULL" + timeDefault + "," +
                "updated_at " + timeType + " NOT NULL" + timeDefault +
                ")");
        ensureColumn(jdbcTemplate, "kb_document", "source_name " + varchar + " DEFAULT ''");
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
                "feature_tags TEXT DEFAULT '[]'" +
                ")");
        ensureColumn(jdbcTemplate, "attraction", "accessibility " + varchar + " DEFAULT ''");
        ensureColumn(jdbcTemplate, "attraction", "environment " + varchar + " DEFAULT ''");
        ensureColumn(jdbcTemplate, "attraction", "recommended_visit_minutes INTEGER");
        ensureColumn(jdbcTemplate, "attraction", "companion_tags TEXT DEFAULT '[]'");
        ensureColumn(jdbcTemplate, "attraction", "feature_tags TEXT DEFAULT '[]'");
        log.info("[KnowledgeDB] attraction 表已就绪");

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
        initAttractions(jdbcTemplate);
        initKnowledgeDocuments(jdbcTemplate);
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
            jdbcTemplate.update("CREATE INDEX idx_trip_owner_updated ON trip (owner_id, updated_at)");
            jdbcTemplate.update("CREATE INDEX idx_trip_version_trip ON trip_version (trip_id, version_number)");
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
            jdbcTemplate.update("CREATE INDEX idx_trip_idempotency_trip ON trip_idempotency (trip_id)");
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
    }

    private void applyMigration(JdbcTemplate jdbcTemplate, int version, String description,
                                Runnable migration) {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schema_migration WHERE version = ?", Integer.class, version);
        if (applied != null && applied > 0) return;
        try {
            migration.run();
            jdbcTemplate.update("INSERT INTO schema_migration (version, description, applied_at) VALUES (?, ?, ?)",
                    version, description, System.currentTimeMillis());
            log.info("[KnowledgeDB] 已应用 schema migration {}: {}", version, description);
        } catch (Exception error) {
            throw new IllegalStateException("无法应用 schema migration " + version + ": " + description, error);
        }
    }

    private void initAttractions(JdbcTemplate jdbcTemplate) {
        try {
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("attractions.json")) {
                if (is == null) {
                    log.error("[KnowledgeDB] 未找到 attractions.json 资源文件，无法初始化景点数据");
                    return;
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                List<Map<String, Object>> list = mapper.readValue(is, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

                String sql = "INSERT INTO attraction (id, name, display_name, district, category, tags, icon, tone, location, summary, walk, duration, indoor, walk_difficulty, ticket, best_time, amap_query, intro, fit, accessibility, environment, recommended_visit_minutes, companion_tags, feature_tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(id) DO NOTHING";
                int inserted = 0;
                for (Map<String, Object> map : list) {
                    boolean indoor = Boolean.TRUE.equals(map.get("indoor"));
                    // JDBC 将 Integer 1/0 直接写入 PostgreSQL BOOLEAN 会被拒绝；
                    // SQLite 则以整数布尔值保存，因此在数据边界按数据库方言转换。
                    Object indoorValue = databaseSettings.sqlite() ? (indoor ? 1 : 0) : indoor;
                    inserted += jdbcTemplate.update(sql,
                            map.get("id"),
                            map.get("name"),
                            map.getOrDefault("displayName", ""),
                            map.getOrDefault("district", ""),
                            map.getOrDefault("category", ""),
                            mapper.writeValueAsString(map.getOrDefault("tags", Collections.emptyList())),
                            map.getOrDefault("icon", ""),
                            map.getOrDefault("tone", ""),
                            map.getOrDefault("location", ""),
                            map.getOrDefault("summary", ""),
                            map.getOrDefault("walk", ""),
                            map.getOrDefault("duration", ""),
                            indoorValue,
                            map.getOrDefault("walkDifficulty", ""),
                            map.getOrDefault("ticket", ""),
                            map.getOrDefault("bestTime", ""),
                            mapper.writeValueAsString(map.getOrDefault("amapQuery", Collections.emptyMap())),
                            map.getOrDefault("intro", ""),
                            map.getOrDefault("fit", ""),
                            map.getOrDefault("accessibility", ""),
                            map.getOrDefault("environment", ""),
                            map.get("recommendedVisitMinutes"),
                            mapper.writeValueAsString(map.getOrDefault("companionTags", Collections.emptyList())),
                            mapper.writeValueAsString(map.getOrDefault("featureTags", Collections.emptyList()))
                    );
                }
                log.info("[KnowledgeDB] 景点目录已核对：资源 {} 条，本次新增 {} 条", list.size(), inserted);
            }
        } catch (Exception e) {
            log.error("[KnowledgeDB] 初始化景点数据失败: {}", e.getMessage(), e);
        }
    }

    private void initKnowledgeDocuments(JdbcTemplate jdbcTemplate) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kb_document WHERE (status IS NULL OR status <> 'archived')", Integer.class);
            if (count != null && count > 0) {
                log.info("[KnowledgeDB] kb_document 表已有 {} 条记录，跳过初始化数据", count);
                return;
            }
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("knowledge_documents.jsonl")) {
                if (is == null) {
                    log.warn("[KnowledgeDB] 未找到 knowledge_documents.jsonl 资源文件，跳过知识文档初始化");
                    return;
                }
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, java.nio.charset.StandardCharsets.UTF_8));
                String line;
                String now = LocalDateTime.now().format(DT_FMT);
                String sql = "INSERT INTO kb_document (id, title, category, content, tags, file_url, file_md5, source_name, status, vector_status, chunk_count, created_by, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, '', '', ?, 'active', 'pending', 0, 'system-init', ?, ?)";
                int inserted = 0;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    Map<String, Object> doc = mapper.readValue(line, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                    String id = String.valueOf(doc.getOrDefault("docId", java.util.UUID.randomUUID().toString()));
                    String title = String.valueOf(doc.getOrDefault("title", ""));
                    String category = String.valueOf(doc.getOrDefault("topic", "culture"));
                    String content = String.valueOf(doc.getOrDefault("content", ""));
                    List<?> keywords = (List<?>) doc.get("keywords");
                    String tags = mapper.writeValueAsString(keywords != null ? keywords : List.of());
                    String sourceName = String.valueOf(doc.getOrDefault("entityName", "chongqing-knowledge"));
                    jdbcTemplate.update(sql, id, title, category, content, tags, sourceName, now, now);
                    inserted++;
                }
                log.info("[KnowledgeDB] 成功初始化 {} 条重庆知识库文档", inserted);
            }
        } catch (Exception e) {
            log.error("[KnowledgeDB] 初始化知识文档失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 为已存在的 SQLite/PostgreSQL 表补齐新增字段。
     * 新部署会由 CREATE TABLE 创建字段；旧部署会在这里平滑升级。
     */
    private void ensureColumn(JdbcTemplate jdbcTemplate, String tableName, String columnDefinition) {
        try {
            jdbcTemplate.update("ALTER TABLE " + tableName + " ADD COLUMN " + columnDefinition);
            log.info("[KnowledgeDB] 已补齐字段: {}.{}", tableName, columnDefinition.split(" ")[0]);
        } catch (Exception ignored) {
            // SQLite/PostgreSQL 在字段已存在时错误信息不同，已存在即可安全忽略。
        }
    }

    /** 将本地 SQLite 的全量业务表复制到 PostgreSQL；按主键跳过已存在记录。 */
    private void migrateLegacySqlite(JdbcTemplate target) {
        File legacyFile = new File(SQLITE_DIR, "knowledge.db");
        if (!legacyFile.exists()) return;

        try {
            Integer migrated = target.queryForObject(
                    "SELECT COUNT(*) FROM app_config WHERE key = ?", Integer.class, "system.legacy_sqlite_migrated");
            if (migrated != null && migrated > 0) {
                migrateLegacyFormalTrips(target, legacyFile);
                return;
            }
        } catch (Exception e) {
            log.warn("[KnowledgeDB] 检查 SQLite 迁移标记失败: {}", e.getMessage());
        }

        DriverManagerDataSource legacyDataSource = new DriverManagerDataSource();
        legacyDataSource.setDriverClassName("org.sqlite.JDBC");
        legacyDataSource.setUrl(SQLITE_URL);
        JdbcTemplate legacy = new JdbcTemplate(legacyDataSource);
        int total = 0;
        total += migrateTable(legacy, target, "kb_document",
                List.of("id", "title", "category", "content", "tags", "file_url", "file_md5", "status", "vector_status", "chunk_count", "created_by", "created_at", "updated_at"), "id");
        total += migrateTable(legacy, target, "users",
                List.of("id", "username", "password_hash", "role", "status", "nickname", "avatar", "phone", "interests", "created_at", "updated_at"), "id");
        migrateLegacyPasswordMetadata(target);
        total += migrateServiceLogs(legacy, target);
        total += migrateTable(legacy, target, "analytics_report",
                List.of("id", "title", "type", "period_start", "period_end", "status", "data", "created_at"), "id");
        total += migrateTable(legacy, target, "app_config",
                List.of("key", "value", "updated_at"), "key");

        try {
            upsertConfig(target, "system.legacy_sqlite_migrated", "1");
            log.info("[KnowledgeDB] SQLite 全量迁移完成，共迁移 {} 条记录", total);
            migrateLegacyFormalTrips(target, legacyFile);
        } catch (Exception e) {
            log.warn("[KnowledgeDB] 写入迁移标记失败，下次启动将继续检查: {}", e.getMessage());
        }
    }

    /**
     * Early PostgreSQL cutover migrated account and knowledge tables only, while
     * the user-facing "我的行程" page reads formal trip/trip_version tables.
     * Keep this migration separately marked so existing cloud databases can
     * import historical saved trips without rerunning the whole legacy import.
     */
    private void migrateLegacyFormalTrips(JdbcTemplate target, File legacyFile) {
        final String marker = "system.legacy_sqlite_formal_trips_migrated";
        try {
            Integer completed = target.queryForObject(
                    "SELECT COUNT(*) FROM app_config WHERE key = ?", Integer.class, marker);
            if (completed != null && completed > 0) return;

            DriverManagerDataSource legacyDataSource = new DriverManagerDataSource();
            legacyDataSource.setDriverClassName("org.sqlite.JDBC");
            legacyDataSource.setUrl("jdbc:sqlite:" + legacyFile.getAbsolutePath().replace('\\', '/'));
            JdbcTemplate legacy = new JdbcTemplate(legacyDataSource);
            int trips = migrateTable(legacy, target, "trip",
                    List.of("id", "owner_id", "title", "status", "current_version", "created_at", "updated_at"), "id");
            int versions = migrateTable(legacy, target, "trip_version",
                    List.of("id", "trip_id", "version_number", "snapshot", "change_reason", "created_at", "created_by"), "id");
            upsertConfig(target, marker, "1");
            log.info("[KnowledgeDB] 正式行程迁移完成：trip {} 条，trip_version {} 条", trips, versions);
        } catch (Exception error) {
            // 不写 marker，下一次启动将通过 ON CONFLICT 安全续传；不能阻断基础服务启动。
            log.warn("[KnowledgeDB] 正式行程迁移暂未完成，下次启动会继续：{}", error.getMessage());
        }
    }

    /**
     * Classifies password material copied from an old Java database without
     * pretending that a digest alone proves the Node-v1 contract. Only the
     * dedicated Phase 5B snapshot importer may mark a credential as
     * LEGACY_SCRYPT because it also supplies trusted source provenance and the
     * original salt text.
     */
    private void migrateLegacyPasswordMetadata(JdbcTemplate target) {
        try {
            // 远程 PostgreSQL 上逐用户 UPDATE 会放大网络往返；该条件与原有逐行判断等价，
            // 但只需一条语句即可完成遗留凭据的安全标记。
            target.update("UPDATE users SET password_scheme = ?, password_version = ?, " +
                            "password_parameters = ?, rehash_required = 0, reset_required = 1 " +
                            "WHERE NOT (UPPER(COALESCE(password_scheme, '')) = 'BCRYPT' " +
                            "AND COALESCE(password_hash, '') LIKE '$2%') " +
                            "AND UPPER(COALESCE(password_scheme, '')) <> 'LEGACY_SCRYPT'",
                    "UNKNOWN", "unknown-v1", "{}");
        } catch (Exception error) {
            log.warn("[KnowledgeDB] legacy password metadata classification skipped: {}", error.getMessage());
        }
    }

    /** 迁移指定表；目标已存在同主键记录时原子跳过，可在首轮中断后安全重试。 */
    private int migrateTable(JdbcTemplate legacy, JdbcTemplate target, String table,
                             List<String> columns, String keyColumn) {
        String columnSql = String.join(", ", columns);
        List<Map<String, Object>> rows;
        try {
            rows = legacy.queryForList("SELECT " + columnSql + " FROM " + table);
        } catch (Exception e) {
            return 0;
        }
        String placeholders = String.join(", ", Collections.nCopies(columns.size(), "?"));
        // 不能先 SELECT 再 INSERT：首轮迁移中断或并发启动时，两步之间仍可能写入相同主键。
        // SQLite 与 PostgreSQL 都支持 ON CONFLICT DO NOTHING，使用数据库原子判重保证可重试。
        String insertSql = "INSERT INTO " + table + " (" + columnSql + ") VALUES (" + placeholders + ") ON CONFLICT DO NOTHING";
        List<Object[]> batchArgs = rows.stream()
                .map(row -> columns.stream().map(row::get).toArray())
                .toList();
        int migrated = 0;
        for (int result : target.batchUpdate(insertSql, batchArgs)) {
            if (result != Statement.EXECUTE_FAILED && result != 0) {
                migrated++;
            }
        }
        return migrated;
    }

    /** 兼容早期 service_log 表没有 user_id 列的 SQLite 版本。 */
    private int migrateServiceLogs(JdbcTemplate legacy, JdbcTemplate target) {
        try {
            List<String> columns = legacy.query("PRAGMA table_info(service_log)", (rs, rowNum) -> rs.getString("name"));
            if (columns.contains("user_id")) {
                return migrateTable(legacy, target, "service_log",
                        List.of("id", "session_id", "user_id", "question", "emotion", "intent", "response_time_ms", "created_at"), "id");
            }
        } catch (Exception ignored) {
            // 旧数据库不存在 service_log 时直接跳过。
        }
        return migrateTable(legacy, target, "service_log",
                List.of("id", "session_id", "question", "emotion", "intent", "response_time_ms", "created_at"), "id");
    }

    /** 跨数据库实现 app_config 的更新或插入。 */
    private void upsertConfig(JdbcTemplate target, String key, String value) {
        String now = LocalDateTime.now().format(DT_FMT);
        int updated = target.update("UPDATE app_config SET value = ?, updated_at = ? WHERE key = ?", value, now, key);
        if (updated == 0) target.update("INSERT INTO app_config (key, value, updated_at) VALUES (?, ?, ?)", key, value, now);
    }

    /** 解析显式 JDBC URL、DATABASE_URL 或 Zeabur PostgreSQL 主机变量。 */
    private DatabaseSettings resolveSettings() {
        String url = firstNonBlank(configuredUrl, databaseUrl, postgresUrl, postgresConnectionString, postgresJdbcUrl, postgresUri);
        String type = configuredType == null ? "sqlite" : configuredType.trim().toLowerCase(Locale.ROOT);
        String username = configuredUsername;
        String password = configuredPassword;

        if (url.isBlank() && !postgresHost.isBlank()) {
            type = "postgresql";
            url = "jdbc:postgresql://" + postgresHost + ":" + postgresPort + "/" + postgresDatabase;
            username = firstNonBlank(username, postgresUser);
            password = firstNonBlank(password, postgresPassword);
        }
        if (url.isBlank()) return new DatabaseSettings("sqlite", SQLITE_URL, "org.sqlite.JDBC", "", "");

        url = normalizeUrl(url);
        if (type.equals("postgres") || type.equals("postgresql") || url.startsWith("jdbc:postgresql:")) {
            return new DatabaseSettings("postgresql", url, "org.postgresql.Driver", username, password);
        }
        throw new IllegalStateException("当前只支持 PostgreSQL，收到数据库配置: " + type + " / " + url);
    }

    /** 将 postgres:// 和 postgresql:// 转换为 JDBC URL。 */
    private String normalizeUrl(String url) {
        if (url.startsWith("postgres://")) return "jdbc:postgresql://" + url.substring("postgres://".length());
        if (url.startsWith("postgresql://")) return "jdbc:postgresql://" + url.substring("postgresql://".length());
        return url;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private record DatabaseSettings(String type, String url, String driverClass, String username, String password) {
        boolean sqlite() { return "sqlite".equals(type); }
        String safeUrl() {
            return url
                    .replaceFirst("(?i)(://)[^/@]+@", "$1***:***@")
                    .replaceAll("(?i)(password|pass|pwd)=[^&]+", "$1=***");
        }
    }
}
