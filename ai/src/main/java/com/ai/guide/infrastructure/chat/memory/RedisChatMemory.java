package com.ai.guide.infrastructure.chat.memory;

import com.ai.guide.common.context.UserContext;
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
 * 基于 Redis 的 Spring AI ChatMemory 基础设施实现
 * 消息以 JSON 形式存入 Redis List，会话记录在 ZSet
 * 支持异步写入
 *
 * 架构定位：COLA 基础设施层 (Infrastructure Layer)
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
     * 兼容性保留消息记录类型定义
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
     * 获取当前用户的会话 ZSet key（匿名游客不写入全局列表）
     */
    private String sessionsKey() {
        if (UserContext.isAnonymous()) {
            return null;
        }
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank() || "null".equalsIgnoreCase(userId) || "anonymous".equalsIgnoreCase(userId)) {
            return null;
        }
        return SESSIONS_KEY_PREFIX + userId;
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
            String sKey = sessionsKey();
            if (sKey != null) {
                redisTemplate.opsForZSet().add(
                        sKey,
                        conversationId,
                        Instant.now().toEpochMilli());
            }
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
        if (conversationId == null) return;
        synchronized (conversationId.intern()) {
            String key = MESSAGE_KEY_PREFIX + conversationId;
            try {
                for (Message message : messages) {
                    String json = serializeMessage(message);
                    redisTemplate.opsForList().rightPush(key, json);
                }
                if (userId != null && !userId.isBlank() && !"null".equalsIgnoreCase(userId) && !"anonymous".equalsIgnoreCase(userId)) {
                    String sessionsKey = SESSIONS_KEY_PREFIX + userId;
                    redisTemplate.opsForZSet().add(sessionsKey, conversationId, Instant.now().toEpochMilli());
                }
            } catch (Exception e) {
                log.error("[RedisChatMemory] 异步写入失败: {}", e.getMessage());
            }
        }
    }

    /**
     * 获取会话最近 lastN 条消息
     */
    @Override
    public List<Message> get(String conversationId, int lastN) {
        String key = messageKey(conversationId);
        try {
            List<String> allMessages = redisTemplate.opsForList().range(key, -Math.max(1,lastN), -1);
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
        redisTemplate.delete(conversationId + ":summary_snapshot");
        redisTemplate.delete(conversationId + ":summary");
        redisTemplate.delete(conversationId + ":compressed_count");
        String sKey = sessionsKey();
        if (sKey != null) {
            redisTemplate.opsForZSet().remove(sKey, conversationId);
        }
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

    public record HistorySnapshot(long total, List<Message> messages) {}
    public HistorySnapshot historySnapshot(String sessionKey, int lastN) {
        try {
            var script=new org.springframework.data.redis.core.script.DefaultRedisScript<List>(
                "local total=redis.call('LLEN',KEYS[1]); local rows=redis.call('LRANGE',KEYS[1],-tonumber(ARGV[1]),-1); table.insert(rows,1,tostring(total)); return rows",List.class);
            List<?> rows=redisTemplate.execute(script,List.of(messageKey(sessionKey)),String.valueOf(Math.max(1,lastN)));
            if (rows == null || rows.isEmpty()) return null;
            long total=Long.parseLong(String.valueOf(rows.get(0)));var result=new ArrayList<Message>();
            for(int n=1;n<rows.size();n++) result.add(deserializeMessage(String.valueOf(rows.get(n))));
            return new HistorySnapshot(total,result);
        } catch(Exception e) { log.warn("[RedisChatMemory] 历史快照读取失败: {}",e.getMessage());return null; }
    }

    public List<Message> getRange(String sessionKey, long from, int count) {
        if (count < 1) return List.of();
        try {
            var rows=redisTemplate.opsForList().range(messageKey(sessionKey),Math.max(0,from),Math.max(0,from)+count-1);
            var result=new ArrayList<Message>();
            if (rows != null) for(String row:rows) result.add(deserializeMessage(row));
            return result;
        } catch(Exception e) { log.warn("[RedisChatMemory] 未覆盖消息段读取失败: {}",e.getMessage());return List.of(); }
    }

    public record SummarySnapshot(String summary, long coveredThrough, List<String> criticalConstraints, Map<String, Long> criticalOrder) {
        public SummarySnapshot {
            criticalConstraints = criticalConstraints == null ? List.of() : List.copyOf(criticalConstraints);
            criticalOrder = criticalOrder == null ? Map.of() : Map.copyOf(criticalOrder);
        }
        public SummarySnapshot(String summary, long coveredThrough) { this(summary, coveredThrough, List.of(), Map.of()); }
        public SummarySnapshot(String summary, long coveredThrough, List<String> constraints) { this(summary, coveredThrough, constraints, Map.of()); }
    }
    /** Same record as the summary: history clear already removes this key, and compression cannot overwrite constraints. */
    public List<String> rememberCriticalConstraints(String sessionKey, List<String> constraints, int maxChars) {
        return rememberCriticalConstraints(sessionKey, constraints, maxChars, System.currentTimeMillis() * 1000L);
    }
    public List<String> rememberCriticalConstraints(String sessionKey, List<String> constraints, int maxBytes, long firstSequence) {
        if (constraints == null || constraints.isEmpty()) {
            return summarySnapshot(sessionKey).criticalConstraints();
        }
        try {
            var script = new org.springframework.data.redis.core.script.DefaultRedisScript<String>(
                    "local raw=redis.call('GET',KEYS[1]); local obj=raw and cjson.decode(raw) or {summary=cjson.null,coveredThrough=0}; " +
                    "local items=obj.criticalConstraints or {}; local order=obj.criticalOrder or {}; if type(items)~='table' then items={} end; if type(order)~='table' then order={} end; " +
                    "if tonumber(ARGV[3])<1000000000000 and redis.call('LLEN',KEYS[2])<=tonumber(ARGV[3]) then return #items==0 and '[]' or cjson.encode(items) end; local seen={}; local size=0; " +
                    "for _,v in ipairs(items) do seen[v]=true; size=size+string.len(v) end; " +
                    "for i,v in ipairs(cjson.decode(ARGV[1])) do if not seen[v] then table.insert(items,v); seen[v]=true; size=size+string.len(v) end; order[v]=math.max(order[v] or 0,tonumber(ARGV[3])+i-1) end; " +
                    "if size>tonumber(ARGV[2]) then return 'OVERFLOW' end; table.sort(items,function(a,b) local x=order[a] or 0; local y=order[b] or 0; return x==y and a<b or x<y end); " +
                    "obj.criticalConstraints=items; obj.criticalOrder=order; redis.call('SET',KEYS[1],cjson.encode(obj)); return cjson.encode(items)", String.class);
            // Redis counts UTF-8 bytes. Use a conservative byte budget rather than silently clipping restrictions.
            String result = redisTemplate.execute(script, List.of(sessionKey + ":summary_snapshot", messageKey(sessionKey)),
                    objectMapper.writeValueAsString(constraints), String.valueOf(maxBytes), String.valueOf(firstSequence));
            if ("OVERFLOW".equals(result)) throw new com.ai.guide.domain.chat.service.ChatContextBudgetPolicy.ContextBudgetException(
                    "本会话的安全与行动限制超过存储预算（UTF-8字节），请精简重复限制或清空当前会话后重新提供；本次未调用模型，已有限制仍保留。");
            if (result == null) throw new IllegalStateException("会话限制暂时无法保存，请稍后重试；本次未调用模型。");
            return objectMapper.readValue(result, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (com.ai.guide.domain.chat.service.ChatContextBudgetPolicy.ContextBudgetException e) { throw e; }
        catch (Exception e) { throw new com.ai.guide.domain.chat.service.ChatContextBudgetPolicy.ContextReadException(); }
    }
    public long messageCount(String sessionKey) {
        try { Long value = redisTemplate.opsForList().size(messageKey(sessionKey)); return value == null ? 0 : value; } catch (Exception e) { return 0; }
    }
    public SummarySnapshot summarySnapshot(String sessionKey) {
        try {
            String json = redisTemplate.opsForValue().get(sessionKey + ":summary_snapshot");
            if (json != null) return objectMapper.readValue(json, SummarySnapshot.class);
            return new SummarySnapshot(redisTemplate.opsForValue().get(sessionKey + ":summary"), 0);
        } catch (Exception e) {
            log.warn("[RedisChatMemory] 摘要与安全限制快照暂不可用");
            throw new com.ai.guide.domain.chat.service.ChatContextBudgetPolicy.ContextReadException();
        }
    }
    public void saveSummarySnapshot(String sessionKey, String summary, long coveredThrough) {
        try {
            String json = objectMapper.writeValueAsString(new SummarySnapshot(summary, coveredThrough));
            // Monotonic cursor prevents an older asynchronous compression overwriting a newer one.
            var script = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>(
                "if redis.call('LLEN',KEYS[2])<tonumber(ARGV[1]) then return 0 end; local old=redis.call('GET',KEYS[1]); local next=cjson.decode(ARGV[2]); if old then local obj=cjson.decode(old); if tonumber(obj.coveredThrough)>=tonumber(ARGV[1]) then return 0 end; next.criticalConstraints=obj.criticalConstraints; next.criticalOrder=obj.criticalOrder end; redis.call('SET',KEYS[1],cjson.encode(next)); return 1", Long.class);
            redisTemplate.execute(script,List.of(sessionKey+":summary_snapshot",messageKey(sessionKey)),String.valueOf(coveredThrough),json);
        } catch (Exception e) { log.warn("[RedisChatMemory] 滚动摘要保存失败: {}", e.getMessage()); }
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
