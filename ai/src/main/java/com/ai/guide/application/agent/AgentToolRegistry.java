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
     * 安全执行指定工具，任何未命中或运行时异常均转为结构化失败结果保留给模型自纠。
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
        try {
            return tool.execute(argumentsJson, context);
        } catch (Exception ex) {
            log.warn("[AgentTool] 工具执行异常 tool={} error={}", toolName, ex.getMessage());
            String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? ex.getClass().getSimpleName()
                    : ex.getMessage();
            String payload = toJson(Map.of(
                    "ok", false,
                    "error", "TOOL_EXECUTION_ERROR",
                    "message", message
            ));
            return AgentToolResult.failure(toolName, payload, "工具执行异常：" + message);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"error\":\"JSON_SERIALIZATION_FAILED\"}";
        }
    }
}
