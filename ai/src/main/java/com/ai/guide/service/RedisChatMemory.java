package com.ai.guide.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatMemory 接口的 Redis 实现
 *
 * Redis 数据结构：
 *
 * chat:session:{sessionId}:messages → List（每条消息存为 JSON 字符串，RPUSH 追加）
 * chat:sessions                    → ZSet（member=sessionId，score=最后活跃时间戳，用于会话索引）
 * 通过 ChatMemory 接口与 Spring AI 框架集成，自动参与聊天上下文构建。
 */
@Component
public class RedisChatMemory implements ChatMemory {

    private static final String MESSAGE_KEY_PREFIX = "chat:session:";
    private static final String MESSAGE_KEY_SUFFIX = ":messages";
    private static final String SESSIONS_KEY = "chat:sessions";

    // 异步写入线程池 —— Redis 写入不阻塞对话响应
    private static final ExecutorService ASYNC_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "redis-async-writer");
        t.setDaemon(true);
        return t;
    });

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemory(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /** 构建消息存储 Key：chat:session:{sessionId}:messages */
    private String messageKey(String conversationId) {
        return MESSAGE_KEY_PREFIX + conversationId + MESSAGE_KEY_SUFFIX;
    }

    /** 同步保存（阻塞当前线程，等待 Redis 响应） */
    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;

        String key = messageKey(conversationId);
        try {
            for (Message msg : messages) {
                String json = serializeMessage(msg);
                redisTemplate.opsForList().rightPush(key, json);
            }
            redisTemplate.opsForZSet().add(SESSIONS_KEY, conversationId, Instant.now().toEpochMilli());
        } catch (Exception e) {
            System.err.println("[RedisChatMemory] 写入失败: " + e.getMessage());
        }
    }

    /** 异步保存 —— 不阻塞对话响应，后台写入 Redis */
    public void addAsync(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        ASYNC_EXECUTOR.submit(() -> add(conversationId, messages));
    }

    /**
     * 从 Redis 读取最近 N 条对话历史
     * 流程：LRANGE 读取最近 lastN 条 JSON → 反序列化 → 返回 Message 列表
     */
    @Override
    public List<Message> get(String conversationId, int lastN) {
        String key = messageKey(conversationId);
        try {
            // LRANGE 从尾部读取最近 lastN 条（计算起始偏移）
            Long lenObj = redisTemplate.opsForList().size(key);
            long len = (lenObj != null) ? lenObj : 0;
            if (len == 0) return List.of();

            long start = Math.max(0, len - lastN);
            List<String> jsonList = redisTemplate.opsForList().range(key, start, len - 1);
            if (jsonList == null || jsonList.isEmpty()) return List.of();

            // 逐条反序列化 JSON → Message 对象
            List<Message> result = new ArrayList<>();
            for (String json : jsonList) {
                result.add(deserializeMessage(json));
            }
            return result;
        } catch (Exception e) {
            System.err.println("[RedisChatMemory] 读取失败（Redis 不可用？）: " + e.getMessage());
            return List.of();
        }
    }

    /** 清除指定会话的全部消息并移除会话索引 */
    @Override
    public void clear(String conversationId) {
        try {
            String key = messageKey(conversationId);
            redisTemplate.delete(key);
            redisTemplate.opsForZSet().remove(SESSIONS_KEY, conversationId);
        } catch (Exception e) {
            System.err.println("[RedisChatMemory] 清除失败: " + e.getMessage());
        }
    }

    // ============ 序列化 / 反序列化 ============

    /** 将 Message 对象序列化为 JSON 字符串 */
    private String serializeMessage(Message message) throws JsonProcessingException {
        MessageRecord record = new MessageRecord(
                message.getMessageType().getValue(),
                message.getContent()
        );
        return objectMapper.writeValueAsString(record);
    }

    /** 将 JSON 字符串反序列化为对应类型的 Message（USER/ASSISTANT/SYSTEM） */
    private Message deserializeMessage(String json) throws JsonProcessingException {
        MessageRecord record = objectMapper.readValue(json, MessageRecord.class);
        return switch (MessageType.fromValue(record.type())) {
            case USER -> new UserMessage(record.content());
            case ASSISTANT -> new AssistantMessage(record.content());
            case SYSTEM -> new SystemMessage(record.content());
            default -> new UserMessage(record.content());
        };
    }

    /**
     * 消息序列化记录（内部使用）
     */
    public record MessageRecord(String type, String content) {}
}
