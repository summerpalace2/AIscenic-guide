package com.ai.guide.domain.planner.gateway;

import java.util.Map;
import java.util.function.Supplier;

/** One authority for the session lifecycle, continuation state and committed apply receipts. */
public interface PlannerConversationStatePort {
    record ApplyReceipt(String fingerprint, int status, Map<String,Object> body) {}
    <T> T withSessionLock(String sessionId, Supplier<T> operation);
    Map<String,Object> continuationState(String sessionId);
    void saveContinuationState(String sessionId, Map<String,Object> state);
    ApplyReceipt applyReceipt(String sessionId, String key);
    void saveApplyReceipt(String sessionId, String key, ApplyReceipt receipt);
    void afterCommit(Runnable action);
}
