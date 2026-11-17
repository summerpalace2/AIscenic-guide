package com.ai.guide.domain.agent.gateway;

import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;

/**
 * 智能体可执行工具的领域能力边界端口（Domain Tool Port）。
 *
 * <p>架构定位：COLA 领域网关端口。每个工具自描述其 JSON Schema、HIL 确认属性与执行逻辑。
 */
public interface AgentToolPort {

    /** 返回工具规格元数据（用于注册到模型及权限校验） */
    AgentToolSpec spec();

    /**
     * 在给定安全上下文中执行工具。
     *
     * @param argumentsJson 模型传入的 JSON 参数
     * @param context       当前请求的可信上下文
     * @return 标准化执行结果（失败时也应返回结构化 {@link AgentToolResult#failure}）
     */
    AgentToolResult execute(String argumentsJson, AgentExecutionContext context);
}
