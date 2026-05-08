package com.ai.guide.api.config;

import jakarta.annotation.PostConstruct;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * Redis 健康检查
 *
 * 启动时通过 PING 命令检测 Redis 连接状态。
 * 连接失败时打印警告但不阻断启动——对话历史功能不可用但聊天不受影响。
 */
@Component
public class RedisHealthCheck {

    private final RedisConnectionFactory connectionFactory;

    public RedisHealthCheck(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @PostConstruct
    public void checkConnection() {
        try {
            String ping = connectionFactory.getConnection().ping();
            System.out.println("[Redis] 连接成功，PING 返回: " + ping);
        } catch (Exception e) {
            System.err.println("[Redis] 连接失败: " + e.getMessage());
            System.err.println("[Redis] 对话历史功能将不可用，但聊天不受影响");
        }
    }
}
