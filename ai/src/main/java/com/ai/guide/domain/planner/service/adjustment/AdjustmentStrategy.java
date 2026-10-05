package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.planner.model.ConversationIntentType;

/**
 * 行程调整意图处理策略标准接口
 */
public interface AdjustmentStrategy {

    /**
     * 判断当前策略是否支持该调整意图类型
     */
    boolean supports(ConversationIntentType intentType);

    /**
     * 执行具体意图的候选方案推导与行程变动计算
     */
    void apply(AdjustmentContext context);
}
