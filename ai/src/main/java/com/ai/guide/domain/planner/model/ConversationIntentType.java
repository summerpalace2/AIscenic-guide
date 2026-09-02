package com.ai.guide.domain.planner.model;

/**
 * 对话式行程调整意图类别枚举
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 * 包含：SUGGEST_REPLACEMENTS, ADD_STOP, REMOVE_STOP, REPLAN_DAY, REDUCE_DAY_DENSITY, APPLY_REPLACEMENT, PLACE_QUESTION, CLARIFICATION, UNKNOWN。
 */
public enum ConversationIntentType {
    PLACE_QUESTION,
    SUGGEST_REPLACEMENTS,
    APPLY_REPLACEMENT,
    ADD_STOP,
    REMOVE_STOP,
    REDUCE_DAY_DENSITY,
    REPLAN_DAY_FOR_CONDITION,
    REPLAN_DAY,
    CLARIFICATION,
    UNKNOWN
}
