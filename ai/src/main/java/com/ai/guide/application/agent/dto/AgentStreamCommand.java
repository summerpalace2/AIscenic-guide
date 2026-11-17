package com.ai.guide.application.agent.dto;

/**
 * 智能体流式对话请求应用层命令对象。
 *
 * <p>架构定位：COLA 应用层（application.agent.dto）。承接 Web 适配层提取的请求参数与可信鉴权身份。
 */
public record AgentStreamCommand(
        String message,
        String sessionId,
        String mode,
        String plannerSessionId,
        String plannerAccessToken,
        String tripId,
        Integer baseRevision,
        String activeCity,
        Integer activeDay,
        String activeStopId,
        String activeStopName,
        String userId,
        String username,
        String role,
        boolean anonymous
) {
    /** 标准化构造命令对象（收敛空串与默认会话/城市字段） */
    public static AgentStreamCommand of(
            String message,
            String sessionId,
            String mode,
            String plannerSessionId,
            String plannerAccessToken,
            String tripId,
            Integer baseRevision,
            String activeCity,
            Integer activeDay,
            String activeStopId,
            String activeStopName,
            String userId,
            String username,
            String role,
            boolean anonymous) {
        return new AgentStreamCommand(
                message == null ? "" : message,
                (sessionId == null || sessionId.isBlank()) ? "default" : sessionId.trim(),
                (mode == null || mode.isBlank()) ? "normal" : mode.trim(),
                plannerSessionId == null ? "" : plannerSessionId.trim(),
                plannerAccessToken == null ? "" : plannerAccessToken.trim(),
                tripId == null ? "" : tripId.trim(),
                baseRevision,
                (activeCity == null || activeCity.isBlank()) ? "重庆" : activeCity.trim(),
                activeDay,
                activeStopId == null ? "" : activeStopId.trim(),
                activeStopName == null ? "" : activeStopName.trim(),
                userId == null ? "" : userId,
                username == null ? "" : username,
                (role == null || role.isBlank()) ? "TOURIST" : role,
                anonymous
        );
    }
}
