package com.ai.guide.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.File;
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
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(databaseSettings.driverClass());
        dataSource.setUrl(databaseSettings.url());
        if (!databaseSettings.username().isBlank()) dataSource.setUsername(databaseSettings.username());
        if (!databaseSettings.password().isBlank()) dataSource.setPassword(databaseSettings.password());

        if (databaseSettings.sqlite()) {
            File dir = new File(SQLITE_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("无法创建 SQLite 数据目录: " + dir.getAbsolutePath());
            }
            log.warn("[KnowledgeDB] 使用本地 SQLite；生产环境请设置 KNOWLEDGE_DB_URL");
        } else {
            log.info("[KnowledgeDB] 使用持久化数据库: type={}, url={}", databaseSettings.type(), databaseSettings.safeUrl());
        }
        return dataSource;
    }

    /** 初始化业务表，并在切换 PostgreSQL 时尝试导入本地 SQLite 数据。 */
    @Bean
    public JdbcTemplate knowledgeJdbcTemplate(DataSource knowledgeDataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(knowledgeDataSource);
        createSchema(jdbcTemplate, databaseSettings.sqlite());
        if (!databaseSettings.sqlite()) migrateLegacySqlite(jdbcTemplate);
        return jdbcTemplate;
    }

    /** 创建知识库、用户、统计、日报和配置表，字段采用 PostgreSQL/SQLite 兼容类型。 */
    private void createSchema(JdbcTemplate jdbcTemplate, boolean sqlite) {
        String timeDefault = sqlite ? " DEFAULT (datetime('now','localtime'))" : " DEFAULT ''";
        String idType = "VARCHAR(128)";
        String varchar = "VARCHAR(255)";
        String timeType = "VARCHAR(32)";

        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS kb_document (" +
                "id " + idType + " PRIMARY KEY," +
                "title " + varchar + " NOT NULL," +
                "category " + varchar + " NOT NULL DEFAULT ''," +
                "content TEXT DEFAULT ''," +
                "tags TEXT DEFAULT '[]'," +
                "file_url TEXT DEFAULT ''," +
                "file_md5 VARCHAR(64) DEFAULT ''," +
                "status " + varchar + " NOT NULL DEFAULT 'active'," +
                "vector_status " + varchar + " NOT NULL DEFAULT 'pending'," +
                "chunk_count INTEGER DEFAULT 0," +
                "created_by " + varchar + " DEFAULT ''," +
                "created_at " + timeType + " NOT NULL" + timeDefault + "," +
                "updated_at " + timeType + " NOT NULL" + timeDefault +
                ")");
        log.info("[KnowledgeDB] kb_document 表已就绪");

        jdbcTemplate.update("CREATE TABLE IF NOT EXISTS users (" +
                "id " + idType + " PRIMARY KEY," +
                "username " + varchar + " UNIQUE NOT NULL," +
                "password_hash TEXT NOT NULL," +
                "role " + varchar + " NOT NULL DEFAULT 'USER'," +
                "status " + varchar + " NOT NULL DEFAULT 'active'," +
                "nickname " + varchar + " DEFAULT ''," +
                "avatar TEXT DEFAULT ''," +
                "phone " + varchar + " DEFAULT ''," +
                "interests TEXT DEFAULT '[]'," +
                "created_at " + timeType + " NOT NULL" + timeDefault + "," +
                "updated_at " + timeType + " NOT NULL" + timeDefault +
                ")");
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
    }

    /** 将本地 SQLite 的全量业务表复制到 PostgreSQL；按主键跳过已存在记录。 */
    private void migrateLegacySqlite(JdbcTemplate target) {
        File legacyFile = new File(SQLITE_DIR, "knowledge.db");
        if (!legacyFile.exists()) return;

        try {
            Integer migrated = target.queryForObject(
                    "SELECT COUNT(*) FROM app_config WHERE key = ?", Integer.class, "system.legacy_sqlite_migrated");
            if (migrated != null && migrated > 0) return;
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
        total += migrateServiceLogs(legacy, target);
        total += migrateTable(legacy, target, "analytics_report",
                List.of("id", "title", "type", "period_start", "period_end", "status", "data", "created_at"), "id");
        total += migrateTable(legacy, target, "app_config",
                List.of("key", "value", "updated_at"), "key");

        try {
            upsertConfig(target, "system.legacy_sqlite_migrated", "1");
            log.info("[KnowledgeDB] SQLite 全量迁移完成，共迁移 {} 条记录", total);
        } catch (Exception e) {
            log.warn("[KnowledgeDB] 写入迁移标记失败，下次启动将继续检查: {}", e.getMessage());
        }
    }

    /** 迁移指定表；目标已存在同主键记录时跳过。 */
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
        String insertSql = "INSERT INTO " + table + " (" + columnSql + ") VALUES (" + placeholders + ")";
        int migrated = 0;
        for (Map<String, Object> row : rows) {
            Object key = row.get(keyColumn);
            Integer exists = target.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + keyColumn + " = ?", Integer.class, key);
            if (exists != null && exists > 0) continue;
            Object[] values = columns.stream().map(row::get).toArray();
            target.update(insertSql, values);
            migrated++;
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
