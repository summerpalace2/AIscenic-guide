package com.ai.guide.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 默认管理员账号初始化
 * 启动时检查并创建默认管理员（仅当用户表中无 ADMIN 时）
 */
@Configuration
public class AdminInitConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminInitConfig.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Value("${admin.default-username:admin}")
    private String defaultAdminUsername;

    @Value("${admin.default-password:admin123}")
    private String defaultAdminPassword;

    @Bean
    public ApplicationRunner adminInitializer(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate) {
        return args -> {
            Integer adminCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role IN ('ADMIN','SUPER_ADMIN')", Integer.class);
            if (adminCount != null && adminCount > 0) {
                log.info("[AdminInit] 管理员已存在，跳过默认创建");
                return;
            }
            String id = UUID.randomUUID().toString();
            String now = LocalDateTime.now().format(DT_FMT);
            String pwHash = ENCODER.encode(defaultAdminPassword);
            jdbcTemplate.update(
                "INSERT INTO users (id, username, password_hash, role, status, created_at, updated_at) VALUES (?, ?, ?, 'ADMIN', 'active', ?, ?)",
                id, defaultAdminUsername, pwHash, now, now);
            log.info("[AdminInit] 默认管理员已创建: {} / {}", defaultAdminUsername, defaultAdminPassword);
        };
    }
}
