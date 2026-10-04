package com.ai.guide.application.chat.dto;

/**
 * AI 流式对话应用层执行指令 (Command)
 *
 * 承接 Adapter 层解析校验后的全部上下文参数，隔离底层 HTTP/Web 依赖。
 */
public record ChatStreamCommand(
        String message,
        String sessionId,
        String mode,
        String plannerSessionId,
        String tripId,
        String currentVersion,
        String activeDay,
        String activeStopId,
        String activeStopName,
        String planContext,
        String userId,
        String username,
        String role
) {
    public static ChatStreamCommand of(String message, String sessionId, String mode,
                                       String plannerSessionId, String tripId, String currentVersion,
                                       String activeDay, String activeStopId, String activeStopName,
                                       String planContext, String userId, String username, String role) {
        return new ChatStreamCommand(
                message,
                (sessionId == null || sessionId.isBlank()) ? "default" : sessionId,
                (mode == null || mode.isBlank()) ? "normal" : mode,
                plannerSessionId == null ? "" : plannerSessionId,
                tripId == null ? "" : tripId,
                currentVersion == null ? "" : currentVersion,
                activeDay == null ? "" : activeDay,
                activeStopId == null ? "" : activeStopId,
                activeStopName == null ? "" : activeStopName,
                planContext == null ? "" : planContext,
                userId,
                username,
                role
        );
    }
}
