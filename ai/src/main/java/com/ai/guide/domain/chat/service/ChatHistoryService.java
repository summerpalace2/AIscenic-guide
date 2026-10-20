package com.ai.guide.domain.chat.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.chat.gateway.ChatHistoryStorePort;
import com.ai.guide.domain.chat.model.ChatHistoryVO;
import com.ai.guide.domain.chat.model.ChatMessageRecord;
import com.ai.guide.domain.chat.model.ConversationVO;
import com.ai.guide.domain.chat.model.ChatHistoryNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** User-scoped chat history query and response mapping. */
@Service
public class ChatHistoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);
    private final ChatHistoryStorePort historyStore;
    private final ObjectMapper objectMapper;

    public ChatHistoryService(ChatHistoryStorePort historyStore, ObjectMapper objectMapper) {
        this.historyStore = historyStore;
        this.objectMapper = objectMapper;
    }

    public List<ConversationVO> getAllSessions() { return getAllSessions(50); }

    public List<ConversationVO> getAllSessions(int limit) {
        String userId = currentUserId();
        if (userId == null) return List.of();
        try {
            List<ConversationVO> result = new ArrayList<>();
            for (ChatHistoryStorePort.ConversationSummary summary : historyStore.listRecentSessions(userId, Math.max(0, limit))) {
                ConversationVO vo = new ConversationVO();
                vo.setSessionId(summary.sessionId());
                vo.setTitle(title(summary.firstMessageJson()));
                vo.setMessageCount((int) Math.min(Integer.MAX_VALUE, Math.max(0L, summary.messageCount())));
                if (summary.lastActiveEpochMillis() != null) {
                    LocalDateTime time = LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(summary.lastActiveEpochMillis()), ZoneId.systemDefault());
                    vo.setCreateTime(time);
                    vo.setLastUpdateTime(time);
                }
                result.add(vo);
            }
            return result;
        } catch (Exception e) {
            log.error("[ChatHistory] 查询会话列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    public List<ChatHistoryVO> getMessages(String sessionId) {
        String ownedSessionId = requireOwnedSession(sessionId);
        try {
            List<String> jsonList = historyStore.getMessages(ownedSessionId);
            if (jsonList == null || jsonList.isEmpty()) return List.of();
            List<ChatHistoryVO> result = new ArrayList<>();
            for (String json : jsonList) {
                try {
                    ChatMessageRecord record = objectMapper.readValue(json, ChatMessageRecord.class);
                    result.add(new ChatHistoryVO(record.type(), record.content(), null));
                } catch (Exception e) {
                    log.warn("[ChatHistory] 消息反序列化失败: {}", e.getMessage());
                }
            }
            return result;
        } catch (Exception e) {
            log.error("[ChatHistory] 查询消息失败: {}", e.getMessage());
            return List.of();
        }
    }

    public void deleteSession(String sessionId) {
        String userId = currentUserId();
        String ownedSessionId = requireOwnedSession(sessionId);
        try {
            historyStore.deleteSession(userId, ownedSessionId);
        } catch (Exception e) {
            log.error("[ChatHistory] 删除会话失败: {}", e.getMessage());
        }
    }

    private String requireOwnedSession(String sessionId) {
        String userId = currentUserId();
        if (userId == null || sessionId == null || sessionId.isBlank()) throw new ChatHistoryNotFoundException();
        String scopedId = sessionId.startsWith(userId + ":") ? sessionId : userId + ":" + sessionId;
        if (!historyStore.ownsSession(userId, scopedId)) throw new ChatHistoryNotFoundException();
        return scopedId;
    }

    private String currentUserId() {
        if (UserContext.isAnonymous()) return null;
        String userId = UserContext.getUserId();
        if (userId == null || userId.isBlank() || "null".equalsIgnoreCase(userId)
                || "anonymous".equalsIgnoreCase(userId)) return null;
        return userId;
    }

    private String title(String firstMessageJson) {
        if (firstMessageJson == null || firstMessageJson.isBlank()) return "新对话";
        try {
            ChatMessageRecord record = objectMapper.readValue(firstMessageJson, ChatMessageRecord.class);
            String content = record.content();
            if (content == null || content.isBlank()) return "新对话";
            return content.length() > 30 ? content.substring(0, 30) + "..." : content;
        } catch (Exception ignored) {
            return "新对话";
        }
    }
}
