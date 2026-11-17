package com.ai.guide.domain.ai.model;

/**
 * 供应商中立的大模型工具定义模型。
 *
 * <p>架构定位：COLA 领域层（domain.ai.model）。用于向底层模型端口声明可调用的工具名称、
 * 语义说明及参数 JSON Schema，隔离具体 AI 框架（如 Spring AI）的注解与回调类型。
 *
 * @param name            工具唯一标识（建议使用下划线命名，如 {@code query_weather_forecast}）
 * @param description     工具用途、适用时机与参数约束说明（供模型决策）
 * @param inputJsonSchema 工具入参的 JSON Schema 字符串
 */
public record LanguageModelToolDefinition(
        String name,
        String description,
        String inputJsonSchema
) {
    public LanguageModelToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        description = description == null ? "" : description;
        inputJsonSchema = (inputJsonSchema == null || inputJsonSchema.isBlank())
                ? "{\"type\":\"object\",\"properties\":{}}"
                : inputJsonSchema;
    }
}
