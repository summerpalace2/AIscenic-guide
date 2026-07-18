package com.ai.guide.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/**
 * 知识库 SQLite 数据库配置
 * 自动创建 DataSource + JdbcTemplate Bean，管理 kb_document 表
 *
 * 对应 Python 版本: backend/app/db/session.py (SQLite 引擎)
 * 原始 Python 由 sleepearlyplease 创建，Java 转化由 summerpalace2 实现
 */
@Configuration
public class KnowledgeDbConfig {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDbConfig.class);

    private static final String DB_DIR = "data";
    private static final String DB_URL = "jdbc:sqlite:data/knowledge.db";

    /**
     * SQLite 数据源（Managed by Spring）
     */
    @Bean
    public DataSource knowledgeDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl(DB_URL);

        // 确保目录存在
        java.io.File dir = new java.io.File(DB_DIR);
        if (!dir.exists()) dir.mkdirs();

        log.info("[KnowledgeDB] SQLite 数据源已初始化: {}", DB_URL);
        return dataSource;
    }

    /**
     * JdbcTemplate for knowledge database
     */
    @Bean
    public JdbcTemplate knowledgeJdbcTemplate(DataSource knowledgeDataSource) {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(knowledgeDataSource);

        // 启动时建表
        jdbcTemplate.update(
            "CREATE TABLE IF NOT EXISTS kb_document (" +
            "id TEXT PRIMARY KEY," +
            "title TEXT NOT NULL," +
            "category TEXT NOT NULL DEFAULT ''," +
            "content TEXT DEFAULT ''," +
            "tags TEXT DEFAULT '[]'," +
            "file_url TEXT DEFAULT ''," +
            "file_md5 TEXT DEFAULT ''," +
            "status TEXT NOT NULL DEFAULT 'active'," +
            "vector_status TEXT NOT NULL DEFAULT 'pending'," +
            "chunk_count INTEGER DEFAULT 0," +
            "created_by TEXT DEFAULT ''," +
            "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))," +
            "updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))" +
            ")"
        );
        log.info("[KnowledgeDB] kb_document 表已就绪");

        // 用户表
        jdbcTemplate.update(
            "CREATE TABLE IF NOT EXISTS users (" +
            "id TEXT PRIMARY KEY," +
            "username TEXT UNIQUE NOT NULL," +
            "password_hash TEXT NOT NULL," +
            "role TEXT NOT NULL DEFAULT 'USER'," +
            "status TEXT NOT NULL DEFAULT 'active'," +
            "nickname TEXT DEFAULT ''," +
            "avatar TEXT DEFAULT ''," +
            "phone TEXT DEFAULT ''," +
            "interests TEXT DEFAULT '[]'," +
            "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))," +
            "updated_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))" +
            ")"
        );
        log.info("[KnowledgeDB] users 表已就绪");

        // 服务日志表
        jdbcTemplate.update(
            "CREATE TABLE IF NOT EXISTS service_log (" +
            "id TEXT PRIMARY KEY," +
            "session_id TEXT NOT NULL DEFAULT ''," +
            "user_id TEXT DEFAULT ''," +
            "question TEXT DEFAULT ''," +
            "emotion TEXT DEFAULT ''," +
            "intent TEXT DEFAULT ''," +
            "response_time_ms INTEGER DEFAULT 0," +
            "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))" +
            ")"
        );
        log.info("[KnowledgeDB] service_log 表已就绪");

        // 分析报告表
        jdbcTemplate.update(
            "CREATE TABLE IF NOT EXISTS analytics_report (" +
            "id TEXT PRIMARY KEY," +
            "title TEXT NOT NULL," +
            "type TEXT NOT NULL DEFAULT 'daily'," +
            "period_start TEXT NOT NULL," +
            "period_end TEXT NOT NULL," +
            "status TEXT NOT NULL DEFAULT 'completed'," +
            "data TEXT DEFAULT '{}'," +
            "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime'))" +
            ")"
        );
        log.info("[KnowledgeDB] analytics_report 表已就绪");

        return jdbcTemplate;
    }
}
