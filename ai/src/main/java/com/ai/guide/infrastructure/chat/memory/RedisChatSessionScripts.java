package com.ai.guide.infrastructure.chat.memory;

import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Atomic conversation clear/write boundary shared by memory and history deletion. */
public final class RedisChatSessionScripts {
    private RedisChatSessionScripts() {}
    public static final DefaultRedisScript<Long> CLEAR = new DefaultRedisScript<>(
            "redis.call('INCR',KEYS[6]); redis.call('DEL',KEYS[1],KEYS[2],KEYS[3],KEYS[4],KEYS[5]); "
            + "if KEYS[7]~='' then redis.call('ZREM',KEYS[7],ARGV[1]) end; return 1",Long.class);
    static final DefaultRedisScript<Long> APPEND = new DefaultRedisScript<>(
            "if (redis.call('GET',KEYS[2]) or '0')~=ARGV[1] then return 0 end; "
            + "for i=4,#ARGV do redis.call('RPUSH',KEYS[1],ARGV[i]) end; "
            + "if KEYS[3]~='' then redis.call('ZADD',KEYS[3],ARGV[2],ARGV[3]) end; return 1",Long.class);
}
