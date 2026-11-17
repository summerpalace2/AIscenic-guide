package com.ai.guide.domain.agent.model;

import java.util.List;
import java.util.Map;

/**
 * 智能体工具单次执行的标准化返回结果。
 *
 * <p>架构定位：COLA 领域层（domain.agent.model）。无论工具成功或失败，均以结构化载荷返回给编排器，
 * 以便将真实观测结果（含失败原因）保留在模型上下文中供下一轮自纠；
 * {@code clientData} 仅透传给前端 SSE / 确认回执用于 UI 状态同步，不进入大模型上下文窗口以保护 KV Cache 与 Token 预算。
 *
 * @param toolName             工具名称
 * @param ok                   是否执行成功
 * @param jsonPayload          回传给大模型的精简结构化 JSON 结果字符串
 * @param summary              面向前端进度条展示的一句话中文摘要
 * @param requiresConfirmation 是否触发人机确认挂起
 * @param citations            工具检索附带的核验引用来源列表
 * @param clientData           仅面向前端消费的结构化附加数据（如新生成行程完整快照）
 */
public record AgentToolResult(
        String toolName,
        boolean ok,
        String jsonPayload,
        String summary,
        boolean requiresConfirmation,
        List<Object> citations,
        Map<String, Object> clientData
) {
    public AgentToolResult {
        toolName = toolName == null ? "" : toolName;
        jsonPayload = (jsonPayload == null || jsonPayload.isBlank()) ? "{}" : jsonPayload;
        summary = summary == null ? "" : summary;
        citations = citations == null ? List.of() : List.copyOf(citations);
        clientData = clientData == null ? Map.of() : Map.copyOf(clientData);
    }

    /** 兼容 Phase 1/2 的 6 参数构造器 */
    public AgentToolResult(
            String toolName,
            boolean ok,
            String jsonPayload,
            String summary,
            boolean requiresConfirmation,
            List<Object> citations) {
        this(toolName, ok, jsonPayload, summary, requiresConfirmation, citations, Map.of());
    }

    /** 构造执行成功结果 */
    public static AgentToolResult success(String toolName, String jsonPayload, String summary) {
        return new AgentToolResult(toolName, true, jsonPayload, summary, false, List.of(), Map.of());
    }

    /** 构造携带前端同步快照（不进 LLM 上下文）的执行成功结果 */
    public static AgentToolResult successWithClientData(String toolName,
                                                        String jsonPayload,
                                                        String summary,
                                                        Map<String, Object> clientData) {
        return new AgentToolResult(toolName, true, jsonPayload, summary, false, List.of(), clientData);
    }

    /** 构造附带核验引用来源的执行成功结果 */
    public static AgentToolResult successWithCitations(String toolName, String jsonPayload, String summary, List<Object> citations) {
        return new AgentToolResult(toolName, true, jsonPayload, summary, false, citations, Map.of());
    }

    /** 构造执行失败结果（保留错误信息给模型，不抹除失败轨迹） */
    public static AgentToolResult failure(String toolName, String jsonPayload, String summary) {
        return new AgentToolResult(toolName, false, jsonPayload, summary, false, List.of(), Map.of());
    }

    /** 构造需要人工确认（HIL）的挂起结果 */
    public static AgentToolResult awaitingConfirmation(String toolName, String jsonPayload, String summary) {
        return new AgentToolResult(toolName, true, jsonPayload, summary, true, List.of(), Map.of());
    }
}
