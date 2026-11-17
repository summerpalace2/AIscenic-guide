package com.ai.guide.domain.ai.model;

import java.util.List;
import java.util.Objects;

/**
 * 支持工具调用的单轮大模型响应结果。
 *
 * <p>架构定位：COLA 领域层（domain.ai.model）。单轮推理可能返回工具调用列表、最终自然语言文本或二者兼有。
 *
 * @param textContent 模型输出的文本内容（若仅发起工具调用，可能为空字符串）
 * @param toolCalls   模型请求执行的工具调用清单（无调用时为空列表）
 * @param usage       本次调用的 Token 消耗统计
 * @param model       实际响应的模型标识
 * @param elapsedMs   本轮推理耗时（毫秒）
 */
public record LanguageModelToolTurn(
        String textContent,
        List<LanguageModelToolCall> toolCalls,
        LanguageModelUsage usage,
        String model,
        long elapsedMs
) {
    public LanguageModelToolTurn {
        textContent = textContent == null ? "" : textContent;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = Objects.requireNonNullElseGet(usage, LanguageModelUsage::unknown);
        elapsedMs = Math.max(0L, elapsedMs);
    }

    /** 判断本轮模型是否发起了工具调用请求 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
