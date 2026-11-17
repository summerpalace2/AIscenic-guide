package com.ai.guide.application.agent.tools;

import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.service.PlannerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全新多天行程生成工具：调用确定性排程引擎从零生成结构化行程草案。
 *
 * <p>模型仅接收精简的天/站点摘要以保护上下文窗口，完整 {@code trip} 快照与 {@code plannerSessionId}
 * 通过 {@code clientData} 经由 SSE {@code tool_result} 同步给前端工作区展示，严禁向模型上下文泄露访问令牌。
 */
@Component
public class CreateTripPlanTool implements AgentToolPort {

    public static final String TOOL_NAME = "create_trip_plan";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "prompt": {
                  "type": "string",
                  "description": "用户的完整出行需求描述，需包含目的地、游玩天数及同行人或偏好线索"
                },
                "durationDays": {
                  "type": "integer",
                  "description": "可选：明确指定的游玩天数（1~7 天）"
                },
                "destination": {
                  "type": "string",
                  "description": "可选：明确指定目标旅游城市，留空默认沿用当前上下文城市"
                },
                "companions": {
                  "type": "string",
                  "description": "可选：同行人群（如 带父母、带孩子、情侣、朋友、独自）"
                },
                "pace": {
                  "type": "string",
                  "description": "可选：步行节奏偏好（如 少走路、轻松、充实）"
                },
                "usePreferences": {
                  "type": "boolean",
                  "description": "是否自动融合当前用户的已保存个人旅行偏好（默认 true）"
                }
              },
              "required": ["prompt"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "根据用户的目的地、天数与偏好通过确定性规划引擎从零生成全新的多天行程草案（生成草稿，不会覆盖已保存正式行程）。",
            SCHEMA,
            false
    );

    private final PlanningApplicationService planningApplicationService;
    private final ObjectMapper objectMapper;

    public CreateTripPlanTool(PlanningApplicationService planningApplicationService, ObjectMapper objectMapper) {
        this.planningApplicationService = planningApplicationService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public AgentToolSpec spec() {
        return SPEC;
    }

    @Override
    public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
        if (context == null || context.anonymous() || context.userId().isBlank()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "LOGIN_REQUIRED",
                    "生成并管理多天专属行程需要先登录账号，请引导用户登录后再发起规划。");
        }
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        String prompt = AgentToolJsonSupport.text(args, "prompt", "");
        if (prompt.isBlank()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "MISSING_PROMPT",
                    "请先提供包含目的地与天数要求的行程规划描述 prompt。");
        }

        Map<String, Object> constraints = new LinkedHashMap<>();
        String destination = AgentToolJsonSupport.text(args, "destination", context.activeCity());
        if (!destination.isBlank()) {
            constraints.put("destination", destination);
        }
        if (args.hasNonNull("durationDays")) {
            int days = Math.max(1, Math.min(7, args.get("durationDays").asInt(2)));
            constraints.put("durationDays", days);
        }
        String companions = AgentToolJsonSupport.text(args, "companions", "");
        if (!companions.isBlank()) {
            constraints.put("companions", companions);
        }
        String pace = AgentToolJsonSupport.text(args, "pace", "");
        if (!pace.isBlank()) {
            constraints.put("pace", pace);
        }
        boolean usePreferences = !args.hasNonNull("usePreferences") || args.get("usePreferences").asBoolean(true);

        PlanRequest planRequest = PlanRequest.builder()
                .prompt(prompt)
                .freeText(prompt)
                .constraints(constraints)
                .usePreferences(usePreferences)
                .idempotencyKey("agent-plan-" + UUID.randomUUID())
                .build();

        PlannerService.ServiceResult serviceResult = planningApplicationService.create(planRequest);
        if (serviceResult == null || serviceResult.status() >= 400 || serviceResult.body() == null) {
            String message = serviceResult != null && serviceResult.body() != null
                    && serviceResult.body().get("message") != null
                    ? String.valueOf(serviceResult.body().get("message"))
                    : "行程规划生成失败，请补充目的地或明确游玩天数后重试。";
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "CREATE_PLAN_FAILED", message);
        }

        Map<String, Object> body = serviceResult.body();
        String newSessionId = body.get("sessionId") == null ? "" : String.valueOf(body.get("sessionId"));
        Object rawTrip = body.get("trip");
        Map<?, ?> tripMap = rawTrip instanceof Map<?, ?> m ? m : Map.of();
        Object version = body.get("currentVersion") != null ? body.get("currentVersion") : tripMap.get("version");
        List<Map<String, Object>> daysSummary = AgentToolJsonSupport.summarizeTripDays(tripMap.get("days"), null);

        Map<String, Object> llmPayload = new LinkedHashMap<>();
        llmPayload.put("ok", true);
        llmPayload.put("plannerSessionId", newSessionId);
        llmPayload.put("currentVersion", version);
        llmPayload.put("title", tripMap.get("title"));
        llmPayload.put("city", tripMap.get("city"));
        llmPayload.put("days", daysSummary);
        if (Boolean.TRUE.equals(body.get("needsClarification"))) {
            llmPayload.put("needsClarification", true);
        }
        if (body.get("constraintWarning") != null) {
            llmPayload.put("constraintWarning", body.get("constraintWarning"));
        }

        Map<String, Object> clientData = new LinkedHashMap<>();
        clientData.put("plannerSessionId", newSessionId);
        if (version != null) clientData.put("currentVersion", version);
        if (rawTrip != null) clientData.put("createdTrip", rawTrip);

        String summary = "已生成新行程草案（" + daysSummary.size() + " 天，v" + (version == null ? 1 : version) + "）";
        return AgentToolResult.successWithClientData(
                TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, llmPayload), summary, clientData);
    }
}
