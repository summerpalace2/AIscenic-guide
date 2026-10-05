package com.ai.guide.domain.planner.model.itinerary;

import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 局部调整两阶段提案不可变对象 (Adjustment Proposal Snapshot)
 */
public record ItineraryProposal(
        String proposalId,
        String sessionId,
        int baseRevision,
        PlanAdjustmentIntent intent,
        ItineraryTrip proposedTrip,
        Map<String, ItineraryTrip> optionPlans,
        List<CandidateReplacementItem> candidateReplacements,
        List<String> changedSegments,
        boolean feasible,
        List<String> reasonCodes,
        Instant createdAt,
        Instant expiresAt
) {
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }
}
