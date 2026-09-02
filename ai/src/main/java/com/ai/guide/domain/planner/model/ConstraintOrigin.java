package com.ai.guide.domain.planner.model;

/**
 * 约束来源枚举（用户显式输入、个人档案画像、系统默认策略）
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 */
public enum ConstraintOrigin {
    USER_CONFIRMED(100),
    USER_TEXT(80),
    PROMPT(80),
    UI_FORM(60),
    REQUEST(60),
    PREFERENCE(40),
    DERIVED(30),
    LLM_INFERRED(20),
    SYSTEM_DEFAULT(10),
    DEFAULT(10);

    private final int priority;

    ConstraintOrigin(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    public boolean higherOrEqualThan(ConstraintOrigin other) {
        return other == null || this.priority >= other.priority;
    }
}

