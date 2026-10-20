package com.ai.guide.domain.planner.gateway;

import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;

/**
 * Proposal lifecycle storage port. Expiry and conversation-epoch validity are
 * domain rules; implementations may cache or serialize proposals as needed.
 */
public interface ProposalRepositoryPort {
    void put(PlanAdjustmentProposal proposal);

    PlanAdjustmentProposal get(String proposalId);

    PlanAdjustmentProposal remove(String proposalId);
}
