package com.ai.guide.domain.planner.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化行程调整意图模型
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 * 架构职责：承载自然语言分类器或大模型解析出的具体调整意图、目标天数、目标站点、替换候选索引及置信度。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PlanAdjustmentIntent {
    private ConversationIntentType type;
    private String operation;
    private String scope;
    private String targetDayReference;
    private Integer dayNumber;
    private String targetStopReference;
    private String targetStopId;
    private String requestedVenueName;
    private String replacementPlaceName;
    private String replacementPlaceId;
    private Integer reduceCount;
    private String condition;
    private String startPlace;
    private Integer timeBudgetMinutes;
    private Integer durationDays;
    private String targetPlace;
    @Builder.Default
    private List<String> preferences = new ArrayList<>();
    @Builder.Default
    private List<String> conditions = new ArrayList<>();
    @Builder.Default
    private List<String> preserveReferences = new ArrayList<>();
    @Builder.Default
    private List<String> missingFields = new ArrayList<>();
    @Builder.Default
    private List<String> pinnedStopIds = new ArrayList<>();
    private String proposalId;
    private String optionId;
    private Integer candidateIndex;
    private String targetAttractionId;
    private String timeSlot;
    /** Half-open adjustment interval; all stops outside it remain unchanged. */
    private Integer targetStartMinute;
    private Integer targetEndMinute;
    /** Server-owned continuation resolution; never accepted from an external DTO. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean timeScopeResolved;
    /** Pure cuisine/dish or venue extracted by the server model; null means legacy fallback. */
    private String cuisineOrPlace;
    /** Tri-state: null means unknown, true a food category, false a specific venue. */
    private Boolean genericDiningCraving;
    private String clarificationQuestion;
    @Builder.Default
    private double confidence = 1.0;
    @Builder.Default
    private boolean requiresClarification = false;
    private String rawMessage;
    @Builder.Default
    private List<String> compoundActions = new ArrayList<>();

    public String getOperation() {
        if (operation != null && !operation.isBlank()) return operation;
        if (type != null) {
            return switch (type) {
                case SUGGEST_REPLACEMENTS -> "REPLACE_STOP";
                case ADD_STOP -> "ADD_STOP";
                case REMOVE_STOP -> "REMOVE_STOP";
                case REPLAN_DAY, REPLAN_DAY_FOR_CONDITION -> "REPLAN_DAY";
                case REDUCE_DAY_DENSITY -> "REDUCE_DENSITY";
                case PLACE_QUESTION -> "QA";
                case APPLY_REPLACEMENT -> "APPLY_REPLACEMENT";
                case CLARIFICATION -> "CLARIFICATION";
                case PLAN -> "PLAN";
                case UNKNOWN -> "UNKNOWN";
            };
        }
        return "UNKNOWN";
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type == null ? ConversationIntentType.UNKNOWN.name() : type.name());
        map.put("operation", getOperation());
        if (scope != null && !scope.isBlank()) map.put("scope", scope);
        if (startPlace != null && !startPlace.isBlank()) map.put("startPlace", startPlace);
        if (timeBudgetMinutes != null) map.put("timeBudgetMinutes", timeBudgetMinutes);
        if (durationDays != null) map.put("durationDays", durationDays);
        if (targetPlace != null && !targetPlace.isBlank()) map.put("targetPlace", targetPlace);
        if (targetDayReference != null && !targetDayReference.isBlank()) map.put("targetDayReference", targetDayReference);
        if (dayNumber != null) map.put("dayNumber", dayNumber);
        if (targetStopReference != null && !targetStopReference.isBlank()) map.put("targetStopReference", targetStopReference);
        if (targetStopId != null && !targetStopId.isBlank()) map.put("targetStopId", targetStopId);
        if (requestedVenueName != null && !requestedVenueName.isBlank()) map.put("requestedVenueName", requestedVenueName);
        if (replacementPlaceName != null && !replacementPlaceName.isBlank()) map.put("replacementPlaceName", replacementPlaceName);
        if (targetAttractionId != null && !targetAttractionId.isBlank()) map.put("targetAttractionId", targetAttractionId);
        if (replacementPlaceId != null && !replacementPlaceId.isBlank()) map.put("replacementPlaceId", replacementPlaceId);
        if (reduceCount != null) map.put("reduceCount", reduceCount);
        if (condition != null && !condition.isBlank()) map.put("condition", condition);
        if (preferences != null && !preferences.isEmpty()) map.put("preferences", preferences);
        if (conditions != null && !conditions.isEmpty()) map.put("conditions", conditions);
        if (preserveReferences != null && !preserveReferences.isEmpty()) map.put("preserveReferences", preserveReferences);
        if (missingFields != null && !missingFields.isEmpty()) map.put("missingFields", missingFields);
        if (pinnedStopIds != null && !pinnedStopIds.isEmpty()) map.put("pinnedStopIds", pinnedStopIds);
        if (proposalId != null && !proposalId.isBlank()) map.put("proposalId", proposalId);
        if (optionId != null && !optionId.isBlank()) map.put("optionId", optionId);
        if (candidateIndex != null) map.put("candidateIndex", candidateIndex);
        if (targetStartMinute != null) map.put("targetStartMinute", targetStartMinute);
        if (targetEndMinute != null) map.put("targetEndMinute", targetEndMinute);
        if (timeSlot != null && !timeSlot.isBlank()) map.put("timeSlot", timeSlot);
        if (cuisineOrPlace != null && !cuisineOrPlace.isBlank()) map.put("cuisineOrPlace", cuisineOrPlace);
        if (genericDiningCraving != null) map.put("genericDiningCraving", genericDiningCraving);
        if (clarificationQuestion != null && !clarificationQuestion.isBlank()) map.put("clarificationQuestion", clarificationQuestion);
        map.put("confidence", confidence);
        map.put("requiresClarification", requiresClarification);
        return map;
    }
}
