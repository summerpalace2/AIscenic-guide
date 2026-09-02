package com.ai.guide.domain.admin.config;

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
 * 管理员账号显式 Bootstrap/Provision 安全初始化配置
 *
 * 所属领域：domain.admin.config（管理后台治理配置）
 * 架构职责：基于外部环境变量显式初始化首个或追加管理员账号，杜绝硬编码凭据与密码日志泄露。
 */
@Configuration
public class AdminInitConfig {

    private static final Logger log = LoggerFactory.getLogger(AdminInitConfig.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    @Value("${admin.bootstrap.mode:disabled}")
    private String bootstrapMode;

    @Value("${admin.bootstrap.username:}")
    private String bootstrapUsername;

    @Value("${admin.bootstrap.password:}")
    private String bootstrapPassword;

    @Bean
    public ApplicationRunner adminInitializer(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate) {
        return args -> {
            Integer adminCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE role IN ('ADMIN','SUPER_ADMIN')", Integer.class);
            String mode = String.valueOf(bootstrapMode).trim().toLowerCase(java.util.Locale.ROOT);
            if (!"one-time".equals(mode) && !"provision".equals(mode)) {
                log.info("[AdminInit] 安全 bootstrap 已禁用；未创建默认管理员");
                return;
            }
            if (adminCount != null && adminCount > 0 && "one-time".equals(mode)) {
                log.info("[AdminInit] 管理员已存在，跳过一次性初始化");
                return;
            }
            if (adminCount != null && adminCount > 0) {
                log.info("[AdminInit] 已存在管理员；provision 模式将按显式账号创建额外管理员");
            }

            // The Web BFF normalizes account login input to lowercase. Store a
            // bootstrap email in the same canonical form so the first admin can
            // use the normal account/password login without a case mismatch.
            String username = bootstrapUsername == null
                    ? ""
                    : bootstrapUsername.trim().toLowerCase(java.util.Locale.ROOT);
            String password = bootstrapPassword == null ? "" : bootstrapPassword;
            if (username.isBlank() || password.isBlank() || password.length() < 12) {
                throw new IllegalStateException("admin.bootstrap.mode=one-time 或 provision 时必须提供至少 12 个字符的外部管理员凭据。");
            }
            Integer usernameCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
            if (usernameCount != null && usernameCount > 0) {
                throw new IllegalStateException("管理员 bootstrap 用户名已被占用；拒绝覆盖现有用户。");
            }

            String id = UUID.randomUUID().toString();
            String now = LocalDateTime.now().format(DT_FMT);
            String pwHash = ENCODER.encode(password);
            try {
                jdbcTemplate.update(
                        "INSERT INTO users (id, username, password_hash, password_scheme, password_version, " +
                                "password_parameters, rehash_required, reset_required, role, status, created_at, updated_at) " +
                                "VALUES (?, ?, ?, 'BCRYPT', '1', '{\"cost\":10}', 0, 0, 'ADMIN', 'active', ?, ?)",
                        id, username, pwHash, now, now);
            } catch (RuntimeException conflict) {
                throw new IllegalStateException("管理员 bootstrap 未完成；用户名可能已被其他实例占用。", conflict);
            }
            log.info("[AdminInit] 管理员已创建（mode={}；用户名已配置；凭据不会写入日志）", mode);
        };
    }
}
