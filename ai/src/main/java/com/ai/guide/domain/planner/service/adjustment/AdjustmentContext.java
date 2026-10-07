package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.PlannerService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方案调整执行上下文，封装当前会话、意图、原始与草稿行程树，以及策略生成的候选方案与诊断指标。
 */
public class AdjustmentContext {

    private final StoredSession session;
    private final PlanAdjustmentIntent intent;
    private final PlanPageContext pageContext;
    private final Map<String, Object> sourceTrip;
    private Map<String, Object> draftTrip;
    private final TravelConstraints constraints;
    private final Map<String, Attraction> attractionsById;
    private final ObjectMapper objectMapper;

    // Output state
    private final List<Map<String, Object>> candidateReplacements = new ArrayList<>();
    private final Map<String, Map<String, Object>> optionPlans = new LinkedHashMap<>();
    // Server-owned expected targets survive repair; never embed these in the user's trip.
    private final Map<String, List<String>> requiredStopIdsByOption = new LinkedHashMap<>();
    private final List<String> changedSegments = new ArrayList<>();
    private List<String> unchangedStops = new ArrayList<>();
    private final List<String> reasonCodes = new ArrayList<>();
    private final List<String> alternatives = new ArrayList<>();
    private final Map<String, Object> adjustmentDiagnostics = new LinkedHashMap<>();
    private final List<Map<String, Object>> rejectedOptionDiagnostics = new ArrayList<>();
    private boolean feasible = true;
    private PlannerService.ServiceResult fatalError = null;

    public AdjustmentContext(StoredSession session,
                             PlanAdjustmentIntent intent,
                             PlanPageContext pageContext,
                             Map<String, Object> sourceTrip,
                             Map<String, Object> draftTrip,
                             TravelConstraints constraints,
                             Map<String, Attraction> attractionsById,
                             ObjectMapper objectMapper) {
        this.session = session;
        this.intent = intent;
        this.pageContext = pageContext;
        this.sourceTrip = sourceTrip;
        this.draftTrip = draftTrip;
        this.constraints = constraints;
        this.attractionsById = attractionsById;
        this.objectMapper = objectMapper;
    }

    public StoredSession getSession() {
        return session;
    }

    public PlanAdjustmentIntent getIntent() {
        return intent;
    }

    public PlanPageContext getPageContext() {
        return pageContext;
    }

    public Map<String, Object> getSourceTrip() {
        return sourceTrip;
    }

    public Map<String, Object> getDraftTrip() {
        return draftTrip;
    }

    public void setDraftTrip(Map<String, Object> draftTrip) {
        this.draftTrip = draftTrip;
    }

    public TravelConstraints getConstraints() {
        return constraints;
    }

    public Map<String, Attraction> getAttractionsById() {
        return attractionsById;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public List<Map<String, Object>> getCandidateReplacements() {
        return candidateReplacements;
    }

    public Map<String, Map<String, Object>> getOptionPlans() {
        return optionPlans;
    }

    public Map<String, List<String>> getRequiredStopIdsByOption() {
        return requiredStopIdsByOption;
    }

    public List<String> getChangedSegments() {
        return changedSegments;
    }

    public List<String> getUnchangedStops() {
        return unchangedStops;
    }

    public void setUnchangedStops(List<String> unchangedStops) {
        this.unchangedStops = unchangedStops;
    }

    public List<String> getReasonCodes() {
        return reasonCodes;
    }

    public List<String> getAlternatives() {
        return alternatives;
    }

    public Map<String, Object> getAdjustmentDiagnostics() {
        return adjustmentDiagnostics;
    }

    public List<Map<String, Object>> getRejectedOptionDiagnostics() {
        return rejectedOptionDiagnostics;
    }

    public boolean isFeasible() {
        return feasible;
    }

    public void setFeasible(boolean feasible) {
        this.feasible = feasible;
    }

    public PlannerService.ServiceResult getFatalError() {
        return fatalError;
    }

    public void setFatalError(PlannerService.ServiceResult fatalError) {
        this.fatalError = fatalError;
    }

    public boolean hasFatalError() {
        return fatalError != null;
    }
}
