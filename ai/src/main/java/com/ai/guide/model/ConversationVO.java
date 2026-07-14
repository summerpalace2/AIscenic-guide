package com.ai.guide.model;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ConversationVO {
    private String sessionId;
    private String title;
    private int messageCount;
    private LocalDateTime createTime;
    private LocalDateTime lastUpdateTime;
}
