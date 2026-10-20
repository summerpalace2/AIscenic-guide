package com.ai.guide.domain.chat.gateway;

import java.util.List;

/** Storage boundary for chat history and per-user conversation indexes. */
public interface ChatHistoryStorePort {
    record ConversationSummary(String sessionId, String firstMessageJson, long messageCount, Long lastActiveEpochMillis) {}

    boolean ownsSession(String userId, String sessionId);
    List<ConversationSummary> listRecentSessions(String userId, int limit);
    List<String> getMessages(String sessionId);
    void deleteSession(String userId, String sessionId);
}
