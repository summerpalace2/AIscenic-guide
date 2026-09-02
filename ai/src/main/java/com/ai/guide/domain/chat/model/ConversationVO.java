package com.ai.guide.domain.chat.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * AI 导游会话摘要视图对象
 *
 * 所属领域：domain.chat.model（AI 导游交互模型）
 * 架构职责：向前端返回会话历史列表中的单条会话概要（包含 sessionId、标题、最后交互时间与消息条数）。
 */
@Data
public class ConversationVO {
    private String sessionId;
    private String title;
    private int messageCount;
    private LocalDateTime createTime;
    private LocalDateTime lastUpdateTime;
}
