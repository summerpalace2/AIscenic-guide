package com.ai.guide.infrastructure.chat.history;

import com.ai.guide.domain.chat.gateway.ChatSummaryStorePort;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/** Redis persistence for chat summary state. */
@Repository
public class RedisChatSummaryStoreAdapter implements ChatSummaryStorePort {
    private static final String SUMMARY_SUFFIX = ":summary";
    private static final String COMPRESSED_COUNT_SUFFIX = ":compressed_count";
    private static final String COMPRESSION_COUNT_SUFFIX = ":compress_count";
    private final RedisTemplate<String, String> redisTemplate;

    public RedisChatSummaryStoreAdapter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public String getSummary(String sessionId) {
        try { return redisTemplate.opsForValue().get(sessionId + SUMMARY_SUFFIX); }
        catch (Exception ignored) { return null; }
    }

    @Override
    public int getCompressedCount(String sessionId) {
        return readInt(sessionId + COMPRESSED_COUNT_SUFFIX);
    }

    @Override
    public int getCompressionCount(String sessionId) {
        return readInt(sessionId + COMPRESSION_COUNT_SUFFIX);
    }

    @Override
    public void saveSummary(String sessionId, String summary, int compressedCount) {
        try {
            redisTemplate.opsForValue().set(sessionId + SUMMARY_SUFFIX, summary);
            redisTemplate.opsForValue().set(sessionId + COMPRESSED_COUNT_SUFFIX, String.valueOf(compressedCount));
        } catch (Exception ignored) { }
    }

    @Override
    public void incrementCompressionCount(String sessionId) {
        try { redisTemplate.opsForValue().increment(sessionId + COMPRESSION_COUNT_SUFFIX, 1); }
        catch (Exception ignored) { }
    }

    @Override
    public void resetCompressionCount(String sessionId) {
        try { redisTemplate.opsForValue().set(sessionId + COMPRESSION_COUNT_SUFFIX, "0"); }
        catch (Exception ignored) { }
    }

    @Override
    public void clear(String sessionId) {
        try {
            redisTemplate.delete(sessionId + SUMMARY_SUFFIX);
            redisTemplate.delete(sessionId + COMPRESSED_COUNT_SUFFIX);
            redisTemplate.delete(sessionId + COMPRESSION_COUNT_SUFFIX);
        } catch (Exception ignored) { }
    }

    private int readInt(String key) {
        try {
            String raw = redisTemplate.opsForValue().get(key);
            return raw == null ? 0 : Integer.parseInt(raw);
        } catch (Exception ignored) { return 0; }
    }
}
