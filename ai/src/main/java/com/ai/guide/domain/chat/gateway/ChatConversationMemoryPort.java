package com.ai.guide.domain.chat.gateway;

import java.util.List;
import java.util.Map;

/** Conversation history and compression-state capability without Redis or Spring AI types. */
public interface ChatConversationMemoryPort {
    enum Role { SYSTEM, USER, ASSISTANT }
    record StoredMessage(Role role, String content) {
        public String getContent() { return content; }
    }
    record HistorySnapshot(long total, List<StoredMessage> messages) {}
    record SummarySnapshot(String summary, long coveredThrough, List<String> criticalConstraints,
                           Map<String, Long> criticalOrder) {}

    void appendAsync(String conversationId, List<StoredMessage> messages);
    String currentWriteEpoch(String sessionKey);
    void appendConfirmed(String conversationId, List<StoredMessage> messages, String expectedEpoch);
    HistorySnapshot loadHistory(String sessionKey, int lastN);
    List<StoredMessage> readRange(String sessionKey, long from, int count);
    SummarySnapshot loadSummarySnapshot(String sessionKey);
    List<String> rememberCriticalConstraints(String sessionKey, List<String> constraints, int maxBytes, long firstSequence);
    List<String> rememberCriticalConstraints(String sessionKey, List<String> constraints, int maxBytes, long firstSequence,String expectedEpoch);
    void saveSummarySnapshot(String sessionKey, String summary, long coveredThrough);
    void saveSummarySnapshot(String sessionKey, String summary, long coveredThrough,String expectedEpoch);
    String getSummary(String sessionKey);
    void saveSummary(String sessionKey, String summary);
    int getCompressedCount(String sessionKey);
    void saveCompressedCount(String sessionKey, int count);
}
