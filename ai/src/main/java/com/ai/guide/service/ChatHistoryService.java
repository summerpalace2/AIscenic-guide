package com.ai.guide.service;

import com.ai.guide.model.ChatHistoryVO;
import com.ai.guide.model.ConversationVO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 前端历史记录的查询服务
 *
 * 从 Redis 读取会话列表和消息，为前端侧边栏提供数据支持。
 * 内部复用 RedisChatMemory 的 MessageRecord 结构进行 JSON 反序列化。
 *
 * Redis 数据结构：
 *
 * chat:session:{sessionId}:messages → List（每条消息 JSON：{"type":"USER/ASSISTANT","content":"..."})
 * chat:sessions                    → ZSet（member=sessionId，score=最后活跃时间戳）
 */
@Service
public class ChatHistoryService {

    private static final String MESSAGE_KEY_PREFIX = "chat:session:";
    private static final String MESSAGE_KEY_SUFFIX = ":messages";
    private static final String SESSIONS_KEY = "chat:sessions";

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public ChatHistoryService(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private String messageKey(String sessionId) {
        return MESSAGE_KEY_PREFIX + sessionId + MESSAGE_KEY_SUFFIX;
    }

    /**
     * 获取所有会话列表（Pipeline 优化版）
     *
     * 传统方式：N 个 session × 3 次 Redis 调用 = 3N 次网络往返
     * Pipeline：所有命令打包为 1 次网络往返，性能提升 10-20x
     */
    public List<ConversationVO> getAllSessions() {
        try {
            Set<String> sessionIds = redisTemplate.opsForZSet()
                    .reverseRange(SESSIONS_KEY, 0, -1);
            if (sessionIds == null || sessionIds.isEmpty()) {
                return List.of();
            }
            List<String> sidList = new ArrayList<>(sessionIds);

            // Pipeline：单次往返发送所有命令，Redis 顺序执行但网络开销为 O(1)
            List<Object> pipeResult = redisTemplate.executePipelined(new SessionCallback<Object>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> Object execute(RedisOperations<K, V> ops) throws DataAccessException {
                    for (String sid : sidList) {
                        String mk = messageKey(sid);
                        ops.opsForList().range((K) mk, 0, 0);
                        ops.opsForList().size((K) mk);
                        ops.opsForZSet().score((K) SESSIONS_KEY, sid);
                    }
                    return null;
                }
            });

            // 组装结果：每 session 对应 3 个返回值 [firstMsg, size, score]
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
            System.out.println("[历史] 返回 " + result.size() + " 个会话 (Pipeline)");
            return result;
        } catch (Exception e) {
            System.err.println("[ChatHistory] 查询会话列表失败: " + e.getMessage());
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
                System.out.println("[历史] 会话 " + sessionId + " 无消息记录");
                return List.of();
            }

            List<ChatHistoryVO> result = new ArrayList<>();
            for (String json : jsonList) {
                try {
                    RedisChatMemory.MessageRecord record =
                            objectMapper.readValue(json, RedisChatMemory.MessageRecord.class);
                    result.add(new ChatHistoryVO(record.type(), record.content(), LocalDateTime.now()));
                } catch (JsonProcessingException ignored) {}
            }
            System.out.println("[历史] 会话 " + sessionId + " 返回 " + result.size() + " 条消息");
            return result;
        } catch (Exception e) {
            System.err.println("[ChatHistory] 查询消息失败: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * 删除指定会话及其所有消息和槽位数据
     */
    public void deleteSession(String sessionId) {
        try {
            redisTemplate.delete(messageKey(sessionId));
            redisTemplate.delete("chat:session:" + sessionId + ":slots");  // 同步清除槽位
            redisTemplate.opsForZSet().remove(SESSIONS_KEY, sessionId);
            System.out.println("[历史] 已删除会话 " + sessionId);
        } catch (Exception e) {
            System.err.println("[ChatHistory] 删除会话失败: " + e.getMessage());
        }
    }

    // ============ 内部方法 ============

    /**
     * 根据 sessionId 构建会话摘要
     * 取第一条用户消息前 30 字作为标题
     */
    private ConversationVO buildConversationVO(String sessionId) {
        String key = messageKey(sessionId);
        List<String> jsonList = redisTemplate.opsForList().range(key, 0, 0); // 只取第一条
        Long size = redisTemplate.opsForList().size(key);
        Double score = redisTemplate.opsForZSet().score(SESSIONS_KEY, sessionId);

        String title = "新对话";
        if (jsonList != null && !jsonList.isEmpty()) {
            try {
                RedisChatMemory.MessageRecord record =
                        objectMapper.readValue(jsonList.get(0), RedisChatMemory.MessageRecord.class);
                String content = record.content();
                title = content.length() > 30 ? content.substring(0, 30) + "..." : content;
            } catch (JsonProcessingException ignored) {}
        }

        ConversationVO vo = new ConversationVO();
        vo.setSessionId(sessionId);
        vo.setTitle(title);
        vo.setMessageCount(size != null ? size.intValue() : 0);

        if (score != null) {
            LocalDateTime time = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(score.longValue()), ZoneId.systemDefault());
            vo.setCreateTime(time);
            vo.setLastUpdateTime(time);
        }
        return vo;
    }
}
