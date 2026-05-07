package com.ai.guide.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 对话历史中的单条消息
 *
 * 从前端视角封装一条消息：角色（USER/ASSISTANT）、内容、时间。
 * 用于历史记录 API 的响应体。
 */
public class ChatHistoryVO {

    private String role;
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime time;

    public ChatHistoryVO() {}

    public ChatHistoryVO(String role, String content, LocalDateTime time) {
        this.role = role;
        this.content = content;
        this.time = time;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public LocalDateTime getTime() { return time; }
    public void setTime(LocalDateTime time) { this.time = time; }
}
