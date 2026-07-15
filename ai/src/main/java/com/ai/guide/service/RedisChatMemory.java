package com.ai.guide.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 基于 Redis 的 Spring AI ChatMemory 实现
 * 消息以 JSON 形式存入 Redis List，会话记录在 ZSet
 * 支持异步写入
 * （由 javap -p -c 反编译 bytecode 恢复）
 */
@Component
public class RedisChatMemory implements ChatMemory {

    /** Redis key 前缀 */
    private static final String MESSAGE_KEY_PREFIX = "chat:history:";

    /** Redis key 后缀 */
    private static final String MESSAGE_KEY_SUFFIX = "";

    /** 会话列表 ZSet key */
    private static final String SESSIONS_KEY = "chat:sessions";

    /** 异步写入线程池（单线程守护线程） */
    private static final ExecutorService ASYNC_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "redis-async-writer");
                thread.setDaemon(true);
                return thread;
            });

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

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
                            ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 构造消息存储 key
     */
    private String messageKey(String conversationId) {
        return MESSAGE_KEY_PREFIX + conversationId + MESSAGE_KEY_SUFFIX;
    }

    /**
     * 同步写入消息
     *
     * @param conversationId 会话 ID
     * @param messages       消息列表
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
            // 更新会话活跃时间
            redisTemplate.opsForZSet().add(
                    SESSIONS_KEY,
                    conversationId,
                    Instant.now().toEpochMilli());
        } catch (Exception e) {
            System.err.println("[RedisChatMemory] 写入失败: " + e.getMessage());
        }
    }

    /**
     * 异步写入消息
     */
    public void addAsync(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        ASYNC_EXECUTOR.submit(() -> add(conversationId, messages));
    }

    /**
     * 获取会话最近 lastN 条消息
     *
     * @param conversationId 会话 ID
     * @param lastN          取最近 N 条
     * @return 消息列表
     */
    @Override
    public List<Message> get(String conversationId, int lastN) {
        String key = messageKey(conversationId);
        try {
            List<String> allMessages = redisTemplate.opsForList().range(key, 0, -1);
            if (allMessages == null || allMessages.isEmpty()) {
                return List.of();
            }

            // 只返回最后 lastN 条
            int fromIndex = Math.max(0, allMessages.size() - lastN);
            List<String> recentMessages = allMessages.subList(fromIndex, allMessages.size());

            List<Message> result = new java.util.ArrayList<>();
            for (String json : recentMessages) {
                Message message = deserializeMessage(json);
                result.add(message);
            }
            return result;
        } catch (Exception e) {
            System.err.println("[RedisChatMemory] 读取失败: " + e.getMessage());
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
        redisTemplate.opsForZSet().remove(SESSIONS_KEY, conversationId);
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
