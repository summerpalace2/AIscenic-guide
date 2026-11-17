package com.ai.guide.domain.agent.model;

import java.util.Map;

/**
 * 待人工确认（HIL）的写操作挂起单领域模型。
 *
 * <p>架构定位：COLA 领域层（domain.agent.model）。记录触发高风险写操作工具时的完整可信上下文、
 * 参数与预览摘要，确保写入前必须经过真实用户显式确认且单次消费。
 *
 * @param confirmId        确认单唯一标识
 * @param ownerKey         归属身份键（防跨用户/跨会话越权确认）
 * @param toolName         待执行的工具名称
 * @param toolCallId       大模型发起调用的关联 ID
 * @param argumentsJson    模型请求的参数 JSON（执行时须经工具二次校验）
 * @param summary          人类可读的操作确认说明
 * @param previewPayload   结构化预览信息（展示变更要点与参数）
 * @param executionContext 挂起时捕获的可信运行时上下文
 * @param createdAtMillis  创建时间戳（毫秒）
 * @param expiresAtMillis  过期时间戳（毫秒）
 */
public record AgentPendingConfirmation(
        String confirmId,
        String ownerKey,
        String toolName,
        String toolCallId,
        String argumentsJson,
        String summary,
        Map<String, Object> previewPayload,
        AgentExecutionContext executionContext,
        long createdAtMillis,
        long expiresAtMillis
) {
    public AgentPendingConfirmation {
        previewPayload = previewPayload == null ? Map.of() : Map.copyOf(previewPayload);
    }
}
