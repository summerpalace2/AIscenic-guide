package com.ai.guide.application.chat.dto;

/** Framework-neutral event emitted by the chat use case. */
public record ChatStreamEvent(String event, String data) { }
