package com.ai.guide.application.chat.dto;

/**
 * 结构化问答应用层执行指令
 */
public record ChatStructuredCommand(
        String message,
        String sessionId,
        String mode,
        String userId
) {
    public static ChatStructuredCommand of(String message, String sessionId, String mode, String userId) {
        return new ChatStructuredCommand(
                message,
                (sessionId == null || sessionId.isBlank()) ? "default" : sessionId,
                (mode == null || mode.isBlank()) ? "normal" : mode,
                userId
        );
    }
}
