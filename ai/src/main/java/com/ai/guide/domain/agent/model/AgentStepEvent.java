package com.ai.guide.domain.agent.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体执行循环中向前端发射的语义化 SSE 事件模型。
 *
 * <p>架构定位：COLA 领域层（domain.agent.model）。与具体 Web/Servlet SSE 类型解耦，
 * 供适配层直接序列化为标准 Server-Sent Events。
 *
 * @param event    SSE 事件名（如 {@code agent_step}、{@code tool_result}、{@code confirm_request}、{@code message}、{@code citations}、{@code done}、{@code error}）
 * @param step     当前执行步数（1 起计数；非步骤事件为 0）
 * @param phase    当前阶段标识
 * @param toolName 关联工具名称（无则为空）
 * @param message  可读提示文案或最终回答正文
 * @param data     结构化扩展载荷
 */
public record AgentStepEvent(
        String event,
        int step,
        String phase,
        String toolName,
        String message,
        Map<String, Object> data
) {
    public AgentStepEvent {
        event = (event == null || event.isBlank()) ? "message" : event;
        phase = phase == null ? "" : phase;
        toolName = toolName == null ? "" : toolName;
        message = message == null ? "" : message;
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    /** 构造“正在思考/即将调用工具”进度事件 */
    public static AgentStepEvent agentStep(int step, String phase, String toolName, String label) {
        return new AgentStepEvent("agent_step", step, phase, toolName, label,
                Map.of("step", step, "phase", phase, "tool", toolName, "label", label));
    }

    /** 构造“工具执行完成”结果摘要事件 */
    public static AgentStepEvent toolResult(int step, String toolName, boolean ok, String summary) {
        return toolResult(step, toolName, ok, summary, Map.of());
    }

    /** 构造携带前端 UI 同步数据的“工具执行完成”事件 */
    public static AgentStepEvent toolResult(int step,
                                            String toolName,
                                            boolean ok,
                                            String summary,
                                            Map<String, Object> extraClientData) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("step", step);
        payload.put("tool", toolName == null ? "" : toolName);
        payload.put("ok", ok);
        payload.put("summary", summary == null ? "" : summary);
        if (extraClientData != null && !extraClientData.isEmpty()) {
            extraClientData.forEach((k, v) -> {
                if (k != null && v != null) {
                    payload.putIfAbsent(k, v);
                }
            });
        }
        return new AgentStepEvent("tool_result", step, "tool_result", toolName, summary, payload);
    }

    /** 构造“需要用户确认（HIL）”阻断事件 */
    public static AgentStepEvent confirmRequest(int step, String toolName, String label, Map<String, Object> payload) {
        return new AgentStepEvent("confirm_request", step, "await_confirmation", toolName, label, payload);
    }

    /** 构造最终自然语言回答文本块 */
    public static AgentStepEvent textDelta(String text) {
        return new AgentStepEvent("message", 0, "answer", "", text, Map.of());
    }

    /** 构造知识核验引用来源事件 */
    public static AgentStepEvent citations(List<Object> citations) {
        return new AgentStepEvent("citations", 0, "citations", "", "",
                Map.of("citations", citations == null ? List.of() : citations));
    }

    /** 构造流结束事件 */
    public static AgentStepEvent done() {
        return new AgentStepEvent("done", 0, "complete", "", "complete", Map.of());
    }

    /** 构造异常结束事件 */
    public static AgentStepEvent error(String message) {
        return new AgentStepEvent("error", 0, "error", "", message,
                Map.of("message", message == null ? "" : message));
    }
}
