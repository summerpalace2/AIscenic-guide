package com.ai.guide.domain.agent.model;

import com.ai.guide.domain.ai.model.LanguageModelToolDefinition;

/**
 * 智能体工具规格定义模型。
 *
 * <p>架构定位：COLA 领域层（domain.agent.model）。描述工具元数据及人机协同（HIL）安全属性。
 *
 * @param name                 工具唯一名称
 * @param description          供模型阅读的功能说明与调用约束
 * @param inputJsonSchema      JSON Schema 入参规范
 * @param requiresConfirmation 是否为写操作/高风险工具（为 true 时须先挂起确认，禁止静默执行）
 */
public record AgentToolSpec(
        String name,
        String description,
        String inputJsonSchema,
        boolean requiresConfirmation
) {
    public AgentToolSpec {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Agent 工具名称不能为空");
        }
        description = description == null ? "" : description;
        inputJsonSchema = (inputJsonSchema == null || inputJsonSchema.isBlank())
                ? "{\"type\":\"object\",\"properties\":{}}"
                : inputJsonSchema;
    }

    /** 转换为大模型端口通用的工具定义对象 */
    public LanguageModelToolDefinition toLanguageModelDefinition() {
        return new LanguageModelToolDefinition(name, description, inputJsonSchema);
    }
}
