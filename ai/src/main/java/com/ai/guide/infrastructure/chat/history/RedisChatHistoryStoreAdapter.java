package com.ai.guide.infrastructure.chat.history;

import com.ai.guide.domain.chat.gateway.ChatHistoryStorePort;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Redis implementation for conversation indexes and message history. */
@Repository
public class RedisChatHistoryStoreAdapter implements ChatHistoryStorePort {
    private static final String MESSAGE_KEY_PREFIX = "chat:history:";
    private static final String SESSIONS_KEY_PREFIX = "chat:sessions:";
    private final RedisTemplate<String, String> redisTemplate;

    public RedisChatHistoryStoreAdapter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean ownsSession(String userId, String sessionId) {
        if (userId == null || userId.isBlank() || sessionId == null || sessionId.isBlank()) return false;
        String scopedId = scope(userId, sessionId);
        return redisTemplate.opsForZSet().score(sessionKey(userId), scopedId) != null;
    }

    @Override
    public List<ConversationSummary> listRecentSessions(String userId, int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) return List.of();
        String indexKey = sessionKey(userId);
        Set<String> ids = redisTemplate.opsForZSet().reverseRange(indexKey, 0, limit - 1L);
        if (ids == null || ids.isEmpty()) return List.of();
        List<String> sessionIds = new ArrayList<>(ids);
        List<Object> pipeline = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            @SuppressWarnings("unchecked")
            public <K, V> Object execute(RedisOperations<K, V> operations) throws DataAccessException {
                for (String id : sessionIds) {
                    operations.opsForList().range((K) messageKey(id), 0, 0);
                    operations.opsForList().size((K) messageKey(id));
                    operations.opsForZSet().score((K) indexKey, id);
                }
                return null;
            }
        });
        if (pipeline == null) return List.of();

        List<ConversationSummary> result = new ArrayList<>();
        for (int i = 0; i < sessionIds.size(); i++) {
            int offset = i * 3;
            Object first = valueAt(pipeline, offset);
            Object count = valueAt(pipeline, offset + 1);
            Object score = valueAt(pipeline, offset + 2);
            String firstMessageJson = first instanceof List<?> values && !values.isEmpty()
                    ? String.valueOf(values.get(0)) : null;
            result.add(new ConversationSummary(sessionIds.get(i), firstMessageJson,
                    count instanceof Number n ? n.longValue() : 0L,
                    score instanceof Number n ? n.longValue() : null));
        }
        return result;
    }

    @Override
    public List<String> getMessages(String sessionId) {
        List<String> messages = redisTemplate.opsForList().range(messageKey(sessionId), 0, -1);
        return messages == null ? List.of() : messages;
    }

    @Override
    public void deleteSession(String userId, String sessionId) {
        redisTemplate.execute(com.ai.guide.infrastructure.chat.memory.RedisChatSessionScripts.CLEAR,
                List.of(messageKey(sessionId),sessionId+":summary_snapshot",sessionId+":summary",sessionId+":compressed_count",
                        sessionId+":compress_count",sessionId+":write_epoch",sessionKey(userId)),sessionId);
    }

    private static String scope(String userId, String sessionId) {
        return sessionId.startsWith(userId + ":") ? sessionId : userId + ":" + sessionId;
    }

    private static String sessionKey(String userId) { return SESSIONS_KEY_PREFIX + userId; }
    private static String messageKey(String sessionId) { return MESSAGE_KEY_PREFIX + sessionId; }
    private static Object valueAt(List<Object> values, int index) {
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }
}
