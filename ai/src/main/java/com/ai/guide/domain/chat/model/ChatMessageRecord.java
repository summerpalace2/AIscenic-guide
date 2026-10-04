package com.ai.guide.domain.chat.model;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import java.util.Collections;
import java.util.Map;

/**
 * 对话历史消息实体记录
 *
 * 架构定位：COLA 领域模型 (Domain Model)
 * 职责：定义存储与反序列化历史会话消息的核心数据载体。
 *
 * @param type    消息角色类型 (如 "user", "assistant", "system")
 * @param content 消息正文文本
 */
public record ChatMessageRecord(String type, String content) implements Message {
    @Override
    public MessageType getMessageType() {
        return MessageType.fromValue(type);
    }

    @Override
    public String getContent() {
        return content;
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Collections.emptyMap();
    }
}
