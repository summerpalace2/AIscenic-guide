package com.ai.guide.domain.ai.model;

import java.util.List;
import java.util.Objects;

/**
 * 供应商中立的大模型对话消息模型。
 *
 * <p>架构定位：COLA 领域层（domain.ai.model）。在保留既有 {@code SYSTEM/USER/ASSISTANT} 文本消息契约的基础上，
 * 扩展支持工具调用轨迹（{@code assistantToolCalls}）与工具执行结果回传（{@code Role.TOOL}）。
 *
 * @param role       消息角色
 * @param content    文本或工具返回载荷内容
 * @param toolCalls  当角色为 {@code ASSISTANT} 且触发工具调用时的调用列表
 * @param toolCallId 当角色为 {@code TOOL} 时对应的工具调用 ID
 * @param toolName   当角色为 {@code TOOL} 时对应的工具名称
 */
public record LanguageModelMessage(
        Role role,
        String content,
        List<LanguageModelToolCall> toolCalls,
        String toolCallId,
        String toolName
) {
    public LanguageModelMessage {
        Objects.requireNonNull(role, "消息角色不能为空");
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 兼容旧版两参数构造器：用于普通文本对话消息 */
    public LanguageModelMessage(Role role, String content) {
        this(role, Objects.requireNonNull(content, "消息内容不能为空"), List.of(), null, null);
    }

    /** 构造携带工具调用请求的助手消息（用于完整保留多轮工具轨迹） */
    public static LanguageModelMessage assistantToolCalls(String content, List<LanguageModelToolCall> toolCalls) {
        return new LanguageModelMessage(Role.ASSISTANT, content == null ? "" : content, toolCalls, null, null);
    }

    /** 构造回传给大模型的工具执行观测消息 */
    public static LanguageModelMessage toolResult(String toolCallId, String toolName, String content) {
        return new LanguageModelMessage(Role.TOOL, content == null ? "" : content, List.of(), toolCallId, toolName);
    }

    /** 消息角色枚举 */
    public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
}
