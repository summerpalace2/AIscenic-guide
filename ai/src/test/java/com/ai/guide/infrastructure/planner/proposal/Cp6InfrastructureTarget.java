package com.ai.guide.infrastructure.planner.proposal;

import java.net.URI;
import java.util.Map;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Test-only target validation. Never consumes production application connection variables. */
final class Cp6InfrastructureTarget {
    static final String PERMISSION = "DISPOSABLE_CP6_TEST_ONLY";
    final String jdbcUrl;
    final String username;
    final String password;
    final String redisHost;
    final int redisPort;
    final String redisPassword;
    final String redisUsername;
    final boolean redisTls;

    Cp6InfrastructureTarget(Map<String, String> env) {
        if (!PERMISSION.equals(env.get("CP6_IT_PERMISSION")))
            throw new IllegalArgumentException("CP6_IT_PERMISSION must explicitly authorize disposable test services");
        jdbcUrl = required(env, "CP6_IT_PG_URL");
        URI target = URI.create(jdbcUrl.replaceFirst("^jdbc:", ""));
        String database = required(env, "CP6_IT_PG_DATABASE");
        if (!database.matches("[A-Za-z0-9_-]+") || !jdbcUrl.startsWith("jdbc:postgresql://") || target.getHost() == null
                || !("/" + database).equals(target.getPath()) || target.getPort() < 1 || target.getPort() > 65535
                || target.getUserInfo() != null || target.getQuery() != null || target.getFragment() != null)
            throw new IllegalArgumentException("PostgreSQL URL must match the explicitly named dedicated test database, with host/port and without URL options");
        username = required(env, "CP6_IT_PG_USER");
        password = required(env, "CP6_IT_PG_PASSWORD");
        redisHost = required(env, "CP6_IT_REDIS_HOST");
        redisPort = Integer.parseInt(required(env, "CP6_IT_REDIS_PORT"));
        if (redisHost.contains("://") || redisHost.contains("/") || redisPort < 1 || redisPort > 65535)
            throw new IllegalArgumentException("Redis requires explicit host and valid port");
        redisPassword = env.getOrDefault("CP6_IT_REDIS_PASSWORD", "");
        redisUsername = env.getOrDefault("CP6_IT_REDIS_USER", "");
        redisTls = Boolean.parseBoolean(env.getOrDefault("CP6_IT_REDIS_TLS", "false"));
    }

    DriverManagerDataSource dataSource(String schema) {
        if (!schema.matches("cp6_[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid isolated schema");
        return new DriverManagerDataSource(jdbcUrl + "?currentSchema=" + schema + "&connectTimeout=5&socketTimeout=15", username, password);
    }

    DriverManagerDataSource adminDataSource() {
        return new DriverManagerDataSource(jdbcUrl + "?connectTimeout=5&socketTimeout=15", username, password);
    }

    LettuceConnectionFactory redisFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(redisHost, redisPort);
        config.setDatabase(15);
        if (!redisUsername.isBlank()) config.setUsername(redisUsername);
        if (!redisPassword.isBlank()) config.setPassword(redisPassword);
        var client = LettuceClientConfiguration.builder().commandTimeout(java.time.Duration.ofSeconds(5))
                .shutdownTimeout(java.time.Duration.ofSeconds(1));
        if (redisTls) client.useSsl();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config, client.build());
        factory.afterPropertiesSet();
        return factory;
    }

    static StringRedisTemplate redis(LettuceConnectionFactory factory) {
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing explicit " + name);
        return value;
    }
}
