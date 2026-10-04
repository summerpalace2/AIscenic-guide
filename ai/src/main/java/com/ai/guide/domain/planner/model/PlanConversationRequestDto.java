package com.ai.guide.domain.planner.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 对话式行程调整与咨询请求命令对象
 *
 * 架构定位：COLA 领域模型 (Domain Model / Command)
 * 职责：封装用户在规划详情页发送的自然语言调整指令、当前选中的站点 ID 与会话上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanConversationRequestDto {
    private String planId;
    private String sessionId;
    @Builder.Default
    private int baseRevision = 1;
    @Builder.Default
    private String message = "";
    private PlanPageContext context;
    @Builder.Default
    private String sessionAccessToken = "";
    @Builder.Default
    private String idempotencyKey = "";

    public String effectiveSessionId() {
        if (sessionId != null && !sessionId.isBlank()) return sessionId.trim();
        return planId == null ? "" : planId.trim();
    }
}
