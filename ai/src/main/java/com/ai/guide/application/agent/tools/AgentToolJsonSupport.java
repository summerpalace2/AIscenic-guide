package com.ai.guide.application.agent.tools;

import com.ai.guide.domain.agent.model.AgentToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体工具 JSON 解析与标准化结果拼装辅助类。
 *
 * <p>集中处理参数解析、默认值读取与错误载荷序列化，避免各工具实现出现重复样板代码。
 */
final class AgentToolJsonSupport {

    private AgentToolJsonSupport() {
    }

    static JsonNode parseArgs(ObjectMapper mapper, String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(argumentsJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具入参不是合法 JSON: " + e.getOriginalMessage(), e);
        }
    }

    static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field)) {
            return fallback;
        }
        String value = node.get(field).asText("").trim();
        return value.isEmpty() ? fallback : value;
    }

    static int intValue(JsonNode node, String field, int fallback, int min, int max) {
        if (node == null || !node.hasNonNull(field)) {
            return fallback;
        }
        int val = node.get(field).asInt(fallback);
        return Math.max(min, Math.min(max, val));
    }

    static String writeJson(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{\"ok\":false,\"error\":\"SERIALIZATION_ERROR\"}";
        }
    }

    static AgentToolResult failure(ObjectMapper mapper, String toolName, String code, String message) {
        String payload = writeJson(mapper, Map.of(
                "ok", false,
                "error", code,
                "message", message
        ));
        return AgentToolResult.failure(toolName, payload, message);
    }

    /** 将行程 days 结构精简为模型可读摘要（剥离大体积折线坐标，控制上下文体积） */
    static List<Map<String, Object>> summarizeTripDays(Object rawDays, Integer filterDay) {
        if (!(rawDays instanceof Collection<?> days)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        int index = 1;
        for (Object dayObj : days) {
            if (!(dayObj instanceof Map<?, ?> dayMap)) {
                index++;
                continue;
            }
            int dayNum = dayMap.get("day") instanceof Number n ? n.intValue() : index;
            if (filterDay != null && dayNum != filterDay) {
                index++;
                continue;
            }
            Map<String, Object> daySummary = new LinkedHashMap<>();
            daySummary.put("day", dayNum);
            if (dayMap.get("title") != null) {
                daySummary.put("title", dayMap.get("title"));
            }
            List<Map<String, Object>> stopList = new ArrayList<>();
            if (dayMap.get("stops") instanceof Collection<?> stops) {
                for (Object stopObj : stops) {
                    if (!(stopObj instanceof Map<?, ?> stop)) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("stopId", stop.get("stopId") != null ? stop.get("stopId") : stop.get("id"));
                    item.put("name", stop.get("name"));
                    if (stop.get("category") != null) item.put("category", stop.get("category"));
                    if (stop.get("period") != null) item.put("period", stop.get("period"));
                    if (stop.get("timeRange") != null) item.put("timeRange", stop.get("timeRange"));
                    if (Boolean.TRUE.equals(stop.get("pinned"))) item.put("pinned", true);
                    if (Boolean.TRUE.equals(stop.get("fixed"))) item.put("fixed", true);
                    stopList.add(item);
                }
            }
            daySummary.put("stops", stopList);
            result.add(daySummary);
            index++;
        }
        return result;
    }
}
