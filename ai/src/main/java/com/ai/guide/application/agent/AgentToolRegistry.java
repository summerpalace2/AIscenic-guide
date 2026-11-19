package com.ai.guide.application.agent;

import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.ai.model.LanguageModelToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 智能体工具注册表与安全执行器。
 *
 * <p>架构定位：COLA 应用层（application.agent）。
 * 核心设计原则（对齐上下文工程规范）：
 * <ul>
 *   <li>启动时按工具名称排序固化工具全集，跨轮次保持顺序与定义字节恒定，以最大化保护模型 KV Cache；</li>
 *   <li>执行异常统一捕获并转化为结构化失败观测结果（{@link AgentToolResult#failure}），保留失败上下文供模型下一轮自纠。</li>
 * </ul>
 */
@Component
public class AgentToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentToolRegistry.class);
    private static final int READ_ONLY_TOOL_MAX_ATTEMPTS = 2;
    private static final long TOOL_RETRY_BACKOFF_MS = 120L;

    private final Map<String, AgentToolPort> toolByName;
    private final List<LanguageModelToolDefinition> fixedToolDefinitions;
    private final ObjectMapper objectMapper;

    public AgentToolRegistry(List<AgentToolPort> tools, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        List<AgentToolPort> sorted = (tools == null ? List.<AgentToolPort>of() : tools).stream()
                .sorted(Comparator.comparing(t -> t.spec().name()))
                .toList();
        Map<String, AgentToolPort> index = new LinkedHashMap<>();
        for (AgentToolPort tool : sorted) {
            String name = tool.spec().name();
            if (index.containsKey(name)) {
                throw new IllegalStateException("检测到重复注册的 Agent 工具名称: " + name);
            }
            index.put(name, tool);
        }
        this.toolByName = Collections.unmodifiableMap(index);
        this.fixedToolDefinitions = sorted.stream()
                .map(t -> t.spec().toLanguageModelDefinition())
                .toList();
    }

    /** 返回顺序与定义全程恒定的工具声明列表（供每轮模型推理复用以保 KV Cache） */
    public List<LanguageModelToolDefinition> toolDefinitions() {
        return fixedToolDefinitions;
    }

    /** 查询指定工具的规格定义 */
    public Optional<AgentToolSpec> specOf(String toolName) {
        AgentToolPort tool = toolName == null ? null : toolByName.get(toolName);
        return tool == null ? Optional.empty() : Optional.of(tool.spec());
    }

    /**
     * 安全执行指定工具，只读/非确认类工具在遇到瞬态超时、TLS 握手中断或服务波动时自动重试一次；
     * 最终未命中或运行时异常均转为结构化失败结果保留给模型自纠。
     */
    public AgentToolResult execute(String toolName, String argumentsJson, AgentExecutionContext context) {
        AgentToolPort tool = toolName == null ? null : toolByName.get(toolName);
        if (tool == null) {
            String payload = toJson(Map.of(
                    "ok", false,
                    "error", "UNKNOWN_TOOL",
                    "message", "工具未注册，请改用已声明的工具: " + toolName
            ));
            return AgentToolResult.failure(toolName, payload, "未找到工具：" + toolName);
        }
        boolean retryAllowed = !tool.spec().requiresConfirmation() && !"create_trip_plan".equals(toolName);
        int maxAttempts = retryAllowed ? READ_ONLY_TOOL_MAX_ATTEMPTS : 1;
        AgentToolResult lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                AgentToolResult result = tool.execute(argumentsJson, context);
                if (result != null && result.ok()) {
                    return result;
                }
                lastFailure = result;
                if (attempt >= maxAttempts || !isTransientToolFailure(result)) {
                    return result != null ? result : AgentToolResult.failure(
                            toolName,
                            toJson(Map.of("ok", false, "error", "TOOL_EMPTY_RESULT", "message", "工具未返回有效结果")),
                            "工具未返回有效结果");
                }
                log.warn("[AgentTool] 检测到瞬态失败，准备第 {} 次重试 tool={} summary={}",
                        attempt + 1, toolName, result == null ? "" : result.summary());
            } catch (IllegalArgumentException ex) {
                log.warn("[AgentTool] 工具参数非法 tool={} error={}", toolName, ex.getMessage());
                String message = safeErrorMessage(ex);
                String payload = toJson(Map.of(
                        "ok", false,
                        "error", "INVALID_ARGUMENTS",
                        "message", message
                ));
                return AgentToolResult.failure(toolName, payload, "工具参数异常：" + message);
            } catch (Exception ex) {
                log.warn("[AgentTool] 工具执行异常 attempt={}/{} tool={} error={}",
                        attempt, maxAttempts, toolName, ex.getMessage());
                String message = safeErrorMessage(ex);
                String payload = toJson(Map.of(
                        "ok", false,
                        "error", "TOOL_EXECUTION_ERROR",
                        "message", message
                ));
                lastFailure = AgentToolResult.failure(toolName, payload, "工具执行异常：" + message);
                if (attempt >= maxAttempts) {
                    return lastFailure;
                }
            }
            if (!sleepBeforeRetry(TOOL_RETRY_BACKOFF_MS)) {
                return lastFailure;
            }
        }
        return lastFailure;
    }

    private static boolean isTransientToolFailure(AgentToolResult result) {
        if (result == null || result.ok()) {
            return false;
        }
        String combined = (result.jsonPayload() == null ? "" : result.jsonPayload())
                + " " + (result.summary() == null ? "" : result.summary());
        return combined.contains("TLS_ERROR")
                || combined.contains("TIMEOUT")
                || combined.contains("ROUTE_UNAVAILABLE")
                || combined.contains("TOOL_EXECUTION_ERROR")
                || combined.contains("高德TLS握手失败")
                || combined.contains("高德接口超时")
                || combined.contains("高德接口暂不可用");
    }

    private static boolean sleepBeforeRetry(long delayMs) {
        try {
            long jitterMs = java.util.concurrent.ThreadLocalRandom.current().nextLong(0L, 80L);
            Thread.sleep(delayMs + jitterMs);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String safeErrorMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"error\":\"JSON_SERIALIZATION_FAILED\"}";
        }
    }
}
