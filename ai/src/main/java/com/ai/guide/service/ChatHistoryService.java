package com.ai.guide.service;

import com.ai.guide.config.UserContext;
import com.ai.guide.model.ChatHistoryVO;
import com.ai.guide.model.ConversationVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 前端历史记录的查询服务
 * 从 Redis 读取会话列表和消息，为前端侧边栏提供数据支持。
 * 内部复用 RedisChatMemory 的 MessageRecord 结构进行 JSON 反序列化。
 *
 * 多账户改造：
 * - 会话 ZSet key 使用 chat:sessions:{userId} 实现用户隔离
 * - 只返回当前用户的会话列表
 *
 * Redis 数据结构：
 * chat:history:{userId}:{sessionId} → List（每条消息 JSON）
 * chat:sessions:{userId}             → ZSet（member=sessionId，score=最后活跃时间戳）
 */
@Service
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

    private static final String MESSAGE_KEY_PREFIX = "chat:history:";
    private static final String SESSIONS_KEY_PREFIX = "chat:sessions:";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public ChatHistoryService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private String messageKey(String sessionId) {
        return MESSAGE_KEY_PREFIX + sessionId;
    }

    private String sessionsKey() {
        return SESSIONS_KEY_PREFIX + UserContext.getUserId();
    }

    /**
     * 获取当前用户的所有会话列表（Pipeline 优化版）
     */
    public List<ConversationVO> getAllSessions() {
        return getAllSessions(50);
    }

    /**
     * 获取当前用户的会话列表（Pipeline 优化版，带分页）
     * @param limit 最大返回会话数（减少 Pipeline 命令数）
     */
    public List<ConversationVO> getAllSessions(int limit) {
        try {
            String sKey = sessionsKey();
            log.info("[DEBUG-HISTORY] userId=" + UserContext.getUserId() + ", sessionsKey=" + sKey);
            Set<String> sessionIds = redisTemplate.opsForZSet()
                    .reverseRange(sKey, 0, -1);
            if (sessionIds == null || sessionIds.isEmpty()) {
                return List.of();
            }
            List<String> sidList = new ArrayList<>(sessionIds);
            List<String> queryList = sidList.size() > limit ? sidList.subList(0, limit) : sidList;

            // Pipeline：单次往返发送所有命令
            List<Object> pipeResult = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> ops) throws DataAccessException {
                    for (String sid : queryList) {
                        String mk = messageKey(sid);
                        ops.opsForList().range((K) mk, 0, 0);
                        ops.opsForList().size((K) mk);
                        ops.opsForZSet().score((K) sessionsKey(), sid);
                    }
                    return null;
                }
            });

            // 组装结果
            List<ConversationVO> result = new ArrayList<>();
            for (int i = 0; i < sidList.size(); i++) {
                int base = i * 3;
                List<String> firstMsgJson = (List<String>) pipeResult.get(base);
                Long msgCount = (Long) pipeResult.get(base + 1);
                Double score = (Double) pipeResult.get(base + 2);

                String title = "新对话";
                if (firstMsgJson != null && !firstMsgJson.isEmpty()) {
                    try {
                        RedisChatMemory.MessageRecord rec =
                                objectMapper.readValue(firstMsgJson.get(0), RedisChatMemory.MessageRecord.class);
                        String c = rec.content();
                        title = c.length() > 30 ? c.substring(0, 30) + "..." : c;
                    } catch (Exception ignored) {}
                }

                ConversationVO vo = new ConversationVO();
                vo.setSessionId(sidList.get(i));
                vo.setTitle(title);
                vo.setMessageCount(msgCount != null ? msgCount.intValue() : 0);
                if (score != null) {
                    LocalDateTime time = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(score.longValue()), ZoneId.systemDefault());
                    vo.setCreateTime(time);
                    vo.setLastUpdateTime(time);
                }
                result.add(vo);
            }
            log.info("[历史] 返回 " + result.size() + " 个会话 (Pipeline) userId=" + UserContext.getUserId());
            return result;
        } catch (Exception e) {
            log.error("[ChatHistory] 查询会话列表失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 获取指定会话的所有消息（user + assistant 成对）
     */
    public List<ChatHistoryVO> getMessages(String sessionId) {
        try {
            String key = messageKey(sessionId);
            List<String> jsonList = redisTemplate.opsForList().range(key, 0, -1);
            if (jsonList == null || jsonList.isEmpty()) {
                log.info("[历史] 会话 " + sessionId + " 无消息记录");
                return List.of();
            }

            List<ChatHistoryVO> result = new ArrayList<>();
            for (String json : jsonList) {
                try {
                    RedisChatMemory.MessageRecord rec =
                            objectMapper.readValue(json, RedisChatMemory.MessageRecord.class);
                    ChatHistoryVO vo = new ChatHistoryVO(rec.type(), rec.content(), null);
                    result.add(vo);
                } catch (Exception e) {
                    log.error("[ChatHistory] 消息反序列化失败: " + e.getMessage());
                }
            }
            log.info("[历史] 会话 " + sessionId + " 返回 " + result.size() + " 条消息");
            return result;
        } catch (Exception e) {
            log.error("[ChatHistory] 查询消息失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除指定会话
     */
    public void deleteSession(String sessionId) {
        try {
            String key = messageKey(sessionId);
            redisTemplate.delete(key);
            redisTemplate.opsForZSet().remove(sessionsKey(), sessionId);
            // 同时清除摘要
            redisTemplate.delete(sessionId + ":summary");
            redisTemplate.delete(sessionId + ":compressed_count");
            log.info("[历史] 已删除会话: " + sessionId);
        } catch (Exception e) {
            log.error("[ChatHistory] 删除会话失败: " + e.getMessage());
        }
    }
}
