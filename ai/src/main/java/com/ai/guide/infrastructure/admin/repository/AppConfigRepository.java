package com.ai.guide.infrastructure.admin.repository;

import com.ai.guide.domain.admin.gateway.AppConfigRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局系统与应用动态配置仓储基础设施层实现类 (App Config Repository Implementation)
 *
 * <p>所属架构层级：COLA 4.0 Infrastructure Layer (基础设施层)</p>
 * <p>核心职责：实现 {@link AppConfigRepositoryPort} 领域端口，通过 Spring JDBC 直接操作数据库 app_config 键值表，
 * 封装 SQL 前缀查询与原子 upsert，隔离底层关系型数据库技术细节。</p>
 */
@Repository
public class AppConfigRepository implements AppConfigRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(AppConfigRepository.class);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public AppConfigRepository(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取指定前缀配置，返回去掉前缀后的键值 Map
     *
     * @param prefix 配置项前缀（如 "digital_human_"、"settings_"）
     * @return 剥离前缀后的键值集合
     */
    @Override
    public Map<String, String> readConfig(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            jdbcTemplate.query("SELECT key, value FROM app_config WHERE key LIKE ?",
                    rs -> {
                        String key = rs.getString("key");
                        result.put(key.substring(prefix.length()), rs.getString("value"));
                    }, prefix + "%");
        } catch (Exception e) {
            log.error("[Admin] 读取配置失败: {}", e.getMessage());
            throw new IllegalStateException("无法读取运营配置", e);
        }
        return result;
    }

    /**
     * 写入配置：键自动补全前缀，存在则更新，不存在则插入
     *
     * @param prefix 配置项前缀
     * @param values 待写入的属性键值集合
     */
    @Override
    public void writeConfig(String prefix, Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        java.util.Objects.requireNonNull(jdbcTemplate.getDataSource())))
                .executeWithoutResult(status -> values.forEach((k, v) -> {
            String key = k.startsWith(prefix) ? k : prefix + k;
            String now = LocalDateTime.now().format(DATE_TIME_FORMATTER);
            String value = v == null ? "" : String.valueOf(v);
            // SQLite (local) and PostgreSQL (production) both support ON CONFLICT.
            // One statement avoids the check-then-insert unique-key race across instances.
            jdbcTemplate.update(
                    "INSERT INTO app_config (key, value, updated_at) VALUES (?, ?, ?) " +
                            "ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                    key, value, now);
        }));
    }
}
