package com.ai.guide.config;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 健康检查组件
 * 通过 ping 命令检测 Redis 连接是否正常
 */
@Component
public class RedisHealthCheck {

    private final StringRedisTemplate redisTemplate;

    public RedisHealthCheck(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 检查 Redis 连通性
     * @return true 表示连接正常
     */
    public boolean check() {
        try {
            String result = redisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(result);
        } catch (Exception e) {
            return false;
        }
    }
}
