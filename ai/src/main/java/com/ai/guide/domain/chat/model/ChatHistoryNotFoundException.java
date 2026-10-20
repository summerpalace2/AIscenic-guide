package com.ai.guide.domain.chat.model;

/** Raised when a chat history is absent or does not belong to the current user. */
public final class ChatHistoryNotFoundException extends RuntimeException {
    public ChatHistoryNotFoundException() {
        super("会话不存在或无权访问");
    }
}
