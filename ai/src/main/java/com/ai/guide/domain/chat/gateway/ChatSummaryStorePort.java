package com.ai.guide.domain.chat.gateway;

/** Persistence boundary for rolling chat summaries and their compression cursor. */
public interface ChatSummaryStorePort {
    String getSummary(String sessionId);
    int getCompressedCount(String sessionId);
    int getCompressionCount(String sessionId);
    void saveSummary(String sessionId, String summary, int compressedCount);
    void incrementCompressionCount(String sessionId);
    void resetCompressionCount(String sessionId);
    void clear(String sessionId);
}
