package com.ai.guide.infrastructure.persistence.config;

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
import java.util.Locale;

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

    @Value("${knowledge.database.type:sqlite}")
    private String configuredType;

    @Value("${knowledge.database.url:}")
    private String configuredUrl;

    @Value("${KNOWLEDGE_DB_URL:}")
    private String knowledgeDbUrl;

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

    @Value("${POSTGRES_DATABASE:}")
    private String postgresDatabase;

    @Value("${POSTGRES_DB:}")
    private String legacyPostgresDatabase;

    @Value("${POSTGRES_USERNAME:}")
    private String postgresUsername;

    @Value("${POSTGRES_USER:}")
    private String postgresUser;

    @Value("${POSTGRES_PASSWORD:}")
    private String postgresPassword;

    private DatabaseSettings databaseSettings;
    @Value("${knowledge.database.sqlite.busy-timeout-ms:5000}")
    private int sqliteBusyTimeoutMs = 5000;

    /** 创建本地 SQLite 或生产 PostgreSQL 数据源。 */
    @Bean
    public DataSource knowledgeDataSource() {
        databaseSettings = resolveSettings();

        if (databaseSettings.sqlite()) {
            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName(databaseSettings.driverClass());
            dataSource.setUrl(databaseSettings.url());
            java.util.Properties properties = new java.util.Properties();
            properties.setProperty("busy_timeout", String.valueOf(Math.max(500, sqliteBusyTimeoutMs)));
            properties.setProperty("journal_mode", "WAL");
            dataSource.setConnectionProperties(properties);
            if (databaseSettings.url().startsWith("jdbc:sqlite:")) {
                String dbPath = databaseSettings.url().substring("jdbc:sqlite:".length());
                File dbFile = new File(dbPath);
                File parentDir = dbFile.getParentFile();
                if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
                    throw new IllegalStateException("无法创建 SQLite 数据目录: " + parentDir.getAbsolutePath());
                }
            } else {
                File dir = new File(SQLITE_DIR);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("无法创建 SQLite 数据目录: " + dir.getAbsolutePath());
                }
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

    /** 组装数据库、版本化 schema、外置种子与旧 SQLite 数据导入。 */
    @Bean
    public JdbcTemplate knowledgeJdbcTemplate(DataSource knowledgeDataSource,
                                              com.ai.guide.infrastructure.persistence.migration.KnowledgeSchemaInitializer schemaInitializer,
                                              com.ai.guide.infrastructure.persistence.migration.KnowledgeSeedDataInitializer seedDataInitializer,
                                              com.ai.guide.infrastructure.persistence.migration.LegacySqliteMigrationService legacyMigration) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(knowledgeDataSource);
        schemaInitializer.initialize(jdbcTemplate, databaseSettings.sqlite());
        seedDataInitializer.initialize(jdbcTemplate, databaseSettings.sqlite());
        if (!databaseSettings.sqlite()) {
            legacyMigration.migrateIfPresent(jdbcTemplate, new File(SQLITE_DIR, "knowledge.db"));
        }
        return jdbcTemplate;
    }

    /** 解析显式 JDBC URL、DATABASE_URL 或 Zeabur PostgreSQL 主机变量。 */
    private DatabaseSettings resolveSettings() {
        String url = firstNonBlank(configuredUrl, knowledgeDbUrl, databaseUrl, postgresUrl, postgresConnectionString, postgresJdbcUrl, postgresUri);
        String type = configuredType == null ? "sqlite" : configuredType.trim().toLowerCase(Locale.ROOT);
        String username = configuredUsername;
        String password = configuredPassword;

        if (url.isBlank() && postgresHost != null && !postgresHost.isBlank()) {
            type = "postgresql";
            url = "jdbc:postgresql://" + postgresHost + ":" + postgresPort + "/"
                    + firstNonBlank(postgresDatabase, legacyPostgresDatabase);
            username = firstNonBlank(username, postgresUsername, postgresUser);
            password = firstNonBlank(password, postgresPassword);
        }
        if (url.isBlank()) {
            if (type.equals("postgres") || type.equals("postgresql")) {
                throw new IllegalStateException("PostgreSQL was selected but no connection URL or POSTGRES_HOST was provided");
            }
            return new DatabaseSettings("sqlite", SQLITE_URL, "org.sqlite.JDBC", "", "");
        }

        url = normalizeUrl(url);
        if (type.equals("sqlite") && url.startsWith("jdbc:sqlite:")) {
            return new DatabaseSettings("sqlite", url, "org.sqlite.JDBC", "", "");
        }
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
