package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.gateway.PlannerConversationStatePort;
import java.util.*;
import java.util.function.Supplier;

/** Explicit standalone-domain compatibility adapter; Spring injects the durable repository instead. */
final class InMemoryPlannerConversationState implements PlannerConversationStatePort {
    private final Map<String,Map<String,Object>> states = new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry<String,Map<String,Object>> entry) { return size()>4096; }
    };
    private final Map<String,ApplyReceipt> receipts = new LinkedHashMap<>() {
        protected boolean removeEldestEntry(Map.Entry<String,ApplyReceipt> entry) { return size()>256; }
    };
    public synchronized <T> T withSessionLock(String sessionId,Supplier<T> action) { return action.get(); }
    public synchronized Map<String,Object> continuationState(String sessionId) { return states.getOrDefault(sessionId,Map.of()); }
    public synchronized void saveContinuationState(String sessionId,Map<String,Object> state) { states.put(sessionId,state); }
    public synchronized ApplyReceipt applyReceipt(String sessionId,String key) { return receipts.get(sessionId+"|"+key); }
    public synchronized void saveApplyReceipt(String sessionId,String key,ApplyReceipt receipt) { receipts.put(sessionId+"|"+key,receipt); }
    public void afterCommit(Runnable action) { action.run(); }
}
