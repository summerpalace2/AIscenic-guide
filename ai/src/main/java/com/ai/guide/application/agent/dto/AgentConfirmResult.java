package com.ai.guide.application.agent.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人机确认（HIL）执行结果模型（携带领域状态与标准响应体，不直接耦合 Spring HTTP 类型）。
 */
public record AgentConfirmResult(int statusCode, Map<String, Object> body) {

    public AgentConfirmResult {
        body = Collections.unmodifiableMap(new LinkedHashMap<>(body == null ? Map.of() : body));
    }

    public static AgentConfirmResult ok(Map<String, Object> body) {
        return new AgentConfirmResult(200, body);
    }

    public static AgentConfirmResult failure(int statusCode, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("modified", false);
        body.put("error", code);
        body.put("message", message);
        return new AgentConfirmResult(statusCode, body);
    }
}
