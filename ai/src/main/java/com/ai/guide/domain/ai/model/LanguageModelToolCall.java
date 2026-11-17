package com.ai.guide.domain.ai.model;

import java.util.Objects;

/**
 * 模型单轮决策发起的工具调用请求。
 *
 * <p>架构定位：COLA 领域层（domain.ai.model）。用于表达大模型在推理后请求执行的一个具体工具及其参数。
 *
 * @param id            本次工具调用的唯一关联 ID（回传 {@code TOOL} 消息时必须原样对齐）
 * @param name          请求调用的工具名称
 * @param argumentsJson 模型生成的工具入参 JSON 字符串
 */
public record LanguageModelToolCall(
        String id,
        String name,
        String argumentsJson
) {
    public LanguageModelToolCall {
        id = id == null ? "" : id;
        Objects.requireNonNull(name, "工具名称不能为空");
        argumentsJson = (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson;
    }
}
