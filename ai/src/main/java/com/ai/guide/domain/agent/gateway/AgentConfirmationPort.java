package com.ai.guide.domain.agent.gateway;

import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentPendingConfirmation;

import java.util.Map;

/**
 * 人机协同（Human-in-the-Loop）待确认操作仓储网关端口。
 *
 * <p>架构定位：COLA 领域网关端口（domain.agent.gateway）。负责保存与原子消费高风险写操作的确认凭证。
 */
public interface AgentConfirmationPort {

    /** 消费确认单结果状态 */
    enum ConsumeStatus {
        /** 校验通过并已原子消费 */
        CONSUMED,
        /** 确认单不存在 */
        NOT_FOUND,
        /** 调用方与确认单归属身份不匹配 */
        FORBIDDEN,
        /** 确认单已超过有效期 */
        EXPIRED,
        /** 确认单已被重复提交消费 */
        ALREADY_CONSUMED
    }

    /** 原子消费结果包装 */
    record ConsumeResult(ConsumeStatus status, AgentPendingConfirmation confirmation) {
        public boolean ok() {
            return status == ConsumeStatus.CONSUMED && confirmation != null;
        }
    }

    /**
     * 创建并持久化一条待用户确认的高风险操作挂起单。
     */
    AgentPendingConfirmation createPending(
            String toolName,
            String toolCallId,
            String argumentsJson,
            String summary,
            Map<String, Object> previewPayload,
            AgentExecutionContext context
    );

    /**
     * 按归属身份校验并原子消费确认单，确保单次生效且防越权。
     */
    ConsumeResult consumeIfOwned(String confirmId, String ownerKey);
}
