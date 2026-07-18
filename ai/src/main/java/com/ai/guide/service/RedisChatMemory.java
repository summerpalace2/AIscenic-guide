package com.ai.guide.service;

import com.ai.guide.config.UserContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 基于 Redis 的 Spring AI ChatMemory 实现
 * 消息以 JSON 形式存入 Redis List，会话记录在 ZSet
 * 支持异步写入
 *
 * 多账户改造：
 * - 会话 ZSet key 使用 chat:sessions:{userId} 实现用户隔离
 * - 摘要存储同样按 userId:sessionId 隔离
 * - 异步写入时提前捕获 userId，避免 ThreadLocal 丢失
 *
 * 性能优化：
 * - 线程池改为 Spring 托管 Bean（优雅关闭）
 */
@Component
public class RedisChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemory.class);

    /** Redis key 前缀 */
    private static final String MESSAGE_KEY_PREFIX = "chat:history:";

    /** 会话列表 ZSet key 前缀（多账户：按 userId 隔离） */
    private static final String SESSIONS_KEY_PREFIX = "chat:sessions:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final ExecutorService asyncExecutor;

    /**
     * 消息记录（用于 JSON 序列化）
     */
    public record MessageRecord(String type, String content) implements Message {
        @Override
        public MessageType getMessageType() {
            return MessageType.fromValue(type);
        }

        @Override
        public String getContent() {
            return content;
        }

        @Override
        public Map<String, Object> getMetadata() {
            return Collections.emptyMap();
        }
    }

    public RedisChatMemory(RedisTemplate<String, String> redisTemplate,
                            ObjectMapper objectMapper,
                            @Qualifier("redisAsyncExecutor") ExecutorService asyncExecutor) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.asyncExecutor = asyncExecutor;
    }

    /**
     * 构造消息存储 key
     */
    private String messageKey(String conversationId) {
        return MESSAGE_KEY_PREFIX + conversationId;
    }

    /**
     * 获取当前用户的会话 ZSet key
     */
    private String sessionsKey() {
        return SESSIONS_KEY_PREFIX + UserContext.getUserId();
    }

    /**
     * 同步写入消息
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        String key = messageKey(conversationId);
        try {
            for (Message message : messages) {
                String json = serializeMessage(message);
                redisTemplate.opsForList().rightPush(key, json);
            }
            redisTemplate.opsForZSet().add(
                    sessionsKey(),
                    conversationId,
                    Instant.now().toEpochMilli());
        } catch (Exception e) {
            log.error("[RedisChatMemory] 写入失败: {}", e.getMessage());
        }
    }

    /**
     * 异步写入消息（多账户安全：提前捕获 userId）
     */
    public void addAsync(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String userId = UserContext.getUserId();
        asyncExecutor.submit(() -> addWithUserId(conversationId, messages, userId));
    }

    /**
     * 带 userId 的写入方法（供异步线程使用）
     */
    private void addWithUserId(String conversationId, List<Message> messages, String userId) {
        String key = MESSAGE_KEY_PREFIX + conversationId;
        String sessionsKey = SESSIONS_KEY_PREFIX + userId;
        try {
            for (Message message : messages) {
                String json = serializeMessage(message);
                redisTemplate.opsForList().rightPush(key, json);
            }
            redisTemplate.opsForZSet().add(sessionsKey, conversationId, Instant.now().toEpochMilli());
        } catch (Exception e) {
            log.error("[RedisChatMemory] 异步写入失败: {}", e.getMessage());
        }
    }

    /**
     * 获取会话最近 lastN 条消息
     */
    @Override
    public List<Message> get(String conversationId, int lastN) {
        String key = messageKey(conversationId);
        try {
            List<String> allMessages = redisTemplate.opsForList().range(key, 0, -1);
            if (allMessages == null || allMessages.isEmpty()) {
                return List.of();
            }

            int fromIndex = Math.max(0, allMessages.size() - lastN);
            List<String> recentMessages = allMessages.subList(fromIndex, allMessages.size());

            List<Message> result = new ArrayList<>();
            for (String json : recentMessages) {
                Message message = deserializeMessage(json);
                result.add(message);
            }
            return result;
        } catch (Exception e) {
            log.error("[RedisChatMemory] 读取失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 清除会话所有消息
     */
    @Override
    public void clear(String conversationId) {
        String key = messageKey(conversationId);
        redisTemplate.delete(key);
        redisTemplate.opsForZSet().remove(sessionsKey(), conversationId);
    }

    /**
     * 保存摘要
     */
    public void saveSummary(String sessionKey, String summary) {
        try {
            redisTemplate.opsForValue().set(sessionKey + ":summary", summary);
        } catch (Exception e) {
            log.error("[RedisChatMemory] 保存摘要失败: {}", e.getMessage());
        }
    }

    /**
     * 获取摘要
     */
    public String getSummary(String sessionKey) {
        try {
            return redisTemplate.opsForValue().get(sessionKey + ":summary");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取压缩计数
     */
    public int getCompressedCount(String sessionKey) {
        try {
            String v = redisTemplate.opsForValue().get(sessionKey + ":compressed_count");
            return v != null ? Integer.parseInt(v) : 0;
        } catch (Exception e) { return 0; }
    }

    /**
     * 保存压缩计数
     */
    public void saveCompressedCount(String sessionKey, int count) {
        try {
            redisTemplate.opsForValue().set(sessionKey + ":compressed_count", String.valueOf(count));
        } catch (Exception ignored) {}
    }

    /**
     * 将 Message 序列化为 JSON
     */
    private String serializeMessage(Message message) throws JsonProcessingException {
        MessageRecord record = new MessageRecord(
                message.getMessageType().getValue(),
                message.getContent());
        return objectMapper.writeValueAsString(record);
    }

    /**
     * 将 JSON 反序列化为 Message
     */
    private Message deserializeMessage(String json) throws JsonProcessingException {
        MessageRecord record = objectMapper.readValue(json, MessageRecord.class);
        switch (record.type()) {
            case "user":
                return new UserMessage(record.content());
            case "assistant":
                return new AssistantMessage(record.content());
            case "system":
                return new SystemMessage(record.content());
            default:
                return new UserMessage(record.content());
        }
    }
}
