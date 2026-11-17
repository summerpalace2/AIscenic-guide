package com.ai.guide.application.agent.dto;

/**
 * 智能体人机确认（HIL）提交命令对象。
 *
 * <p>架构定位：COLA 应用层（application.agent.dto）。用于封装用户对 {@code confirm_request} 卡片的确认或拒绝决策。
 */
public record AgentConfirmCommand(
        String confirmId,
        boolean approved,
        String sessionId,
        String userId,
        String username,
        String role,
        boolean anonymous
) {
    /** 构造归属校验键（需与挂起时 AgentExecutionContext.ownerKey 严格对齐） */
    public String ownerKey() {
        if (!anonymous && userId != null && !userId.isBlank()) {
            return "user:" + userId;
        }
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId.trim();
        return "guest:" + sid;
    }
}
