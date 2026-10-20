package com.ai.guide.infrastructure.admin.config;

import com.ai.guide.domain.user.gateway.PasswordEncoderPort;
import com.ai.guide.domain.user.gateway.UserAccountRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/** Explicit one-time administrator bootstrap adapter. */
@Configuration
public class AdminInitConfig {
    private static final Logger log = LoggerFactory.getLogger(AdminInitConfig.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final String bootstrapMode;
    private final String bootstrapUsername;
    private final String bootstrapPassword;
    private final UserAccountRepositoryPort accounts;
    private final PasswordEncoderPort passwords;

    public AdminInitConfig(@Value("${admin.bootstrap.mode:disabled}") String bootstrapMode,
                           @Value("${admin.bootstrap.username:}") String bootstrapUsername,
                           @Value("${admin.bootstrap.password:}") String bootstrapPassword,
                           UserAccountRepositoryPort accounts,
                           PasswordEncoderPort passwords) {
        this.bootstrapMode = bootstrapMode;
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
        this.accounts = accounts;
        this.passwords = passwords;
    }

    @Bean
    public ApplicationRunner adminInitializer() {
        return args -> {
            int adminCount = accounts.countAdministrators();
            String mode = String.valueOf(bootstrapMode).trim().toLowerCase(Locale.ROOT);
            if (!"one-time".equals(mode) && !"provision".equals(mode)) {
                log.info("[AdminInit] 安全 bootstrap 已禁用；未创建默认管理员");
                return;
            }
            if (adminCount > 0 && "one-time".equals(mode)) {
                log.info("[AdminInit] 管理员已存在，跳过一次性初始化");
                return;
            }
            if (adminCount > 0) log.info("[AdminInit] 已存在管理员；provision 模式将按显式账号创建额外管理员");

            String username = bootstrapUsername == null ? "" : bootstrapUsername.trim().toLowerCase(Locale.ROOT);
            String password = bootstrapPassword == null ? "" : bootstrapPassword;
            if (username.isBlank() || password.isBlank() || password.length() < 12) {
                throw new IllegalStateException("admin.bootstrap.mode=one-time 或 provision 时必须提供至少 12 个字符的外部管理员凭据。");
            }
            if (accounts.usernameExists(username)) throw new IllegalStateException("管理员 bootstrap 用户名已被占用；拒绝覆盖现有用户。");
            String now = LocalDateTime.now().format(DATE_FORMAT);
            try {
                accounts.createAdministrator(UUID.randomUUID().toString(), username, passwords.encode(password), now);
            } catch (RuntimeException conflict) {
                throw new IllegalStateException("管理员 bootstrap 未完成；用户名可能已被其他实例占用。", conflict);
            }
            log.info("[AdminInit] 管理员已创建（mode={}；用户名已配置；凭据不会写入日志）", mode);
        };
    }
}
