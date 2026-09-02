package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 调整方案提案内存存储与有效期管理
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 */
@Component
public class ProposalStore {

    public static final long DEFAULT_TTL_MS = 15 * 60 * 1000L; // 15 minutes
    private static final int MAX_CAPACITY = 500;

    private final Map<String, PlanAdjustmentProposal> store = new ConcurrentHashMap<>();

    public void put(PlanAdjustmentProposal proposal) {
        if (proposal == null || proposal.getProposalId() == null) return;
        if (store.size() >= MAX_CAPACITY) {
            cleanExpired();
            if (store.size() >= MAX_CAPACITY) {
                // Remove arbitrary oldest entry if still full
                String firstKey = store.keySet().iterator().next();
                store.remove(firstKey);
            }
        }
        store.put(proposal.getProposalId(), proposal);
    }

    public PlanAdjustmentProposal get(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) return null;
        PlanAdjustmentProposal proposal = store.get(proposalId);
        if (proposal == null) return null;
        if (proposal.isExpired()) {
            store.remove(proposalId);
            return null;
        }
        return proposal;
    }

    public PlanAdjustmentProposal remove(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) return null;
        return store.remove(proposalId);
    }

    public void cleanExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue().getExpiresAt() < now);
    }

    public int size() {
        cleanExpired();
        return store.size();
    }

    public void clear() {
        store.clear();
    }
}
