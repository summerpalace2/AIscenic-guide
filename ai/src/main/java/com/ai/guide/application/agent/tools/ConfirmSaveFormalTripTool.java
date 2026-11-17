package com.ai.guide.application.agent.tools;

import com.ai.guide.application.planner.PlanningActor;
import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.application.trip.TripApplicationService;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.planner.model.PlannerSaveResult;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.service.TripService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 正式行程保存转正工具（HIL 写操作工具：{@code requiresConfirmation = true}）。
 *
 * <p>仅已登录用户经确认卡批准后，才会将当前规划会话持久化为我的行程中的正式行程；
 * 若用户同时指定了期望标题 {@code title}，在保存后同步更新正式行程标题。
 */
@Component
public class ConfirmSaveFormalTripTool implements AgentToolPort {

    public static final String TOOL_NAME = "confirm_save_formal_trip";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "title": {
                  "type": "string",
                  "description": "可选：用户明确指定的正式行程标题，留空则沿用草案默认标题"
                },
                "changeSummary": {
                  "type": "string",
                  "description": "向用户确认卡片展示的一句话中文保存说明"
                }
              },
              "required": ["changeSummary"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "请求用户确认并将当前行程草稿正式保存到个人行程列表（触发 HIL 确认，游客无法调用）。",
            SCHEMA,
            true
    );

    private final PlanningApplicationService planningApplicationService;
    private final TripApplicationService tripApplicationService;
    private final ObjectMapper objectMapper;

    public ConfirmSaveFormalTripTool(PlanningApplicationService planningApplicationService,
                                     ObjectMapper objectMapper) {
        this(planningApplicationService, objectMapper, null);
    }

    @Autowired
    public ConfirmSaveFormalTripTool(PlanningApplicationService planningApplicationService,
                                     ObjectMapper objectMapper,
                                     @Autowired(required = false) TripApplicationService tripApplicationService) {
        this.planningApplicationService = planningApplicationService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.tripApplicationService = tripApplicationService;
    }

    @Override
    public AgentToolSpec spec() {
        return SPEC;
    }

    @Override
    public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
        if (context == null || context.anonymous() || context.userId().isBlank()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "LOGIN_REQUIRED",
                    "游客模式无法正式保存行程，请先登录账号后再保存。");
        }
        if (!context.hasPlannerSession()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "NO_PLANNER_SESSION",
                    "当前未绑定可保存的行程草稿，请先生成或打开一份行程。");
        }

        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        String desiredTitle = AgentToolJsonSupport.text(args, "title", "");

        PlanningActor actor = new PlanningActor(context.userId(), false);
        PlannerSaveResult result = planningApplicationService.save(
                actor, context.plannerSessionId(), context.plannerAccessToken());
        if (result == null || result.body() == null || result.serviceStatus() >= 400) {
            String message = result != null && result.body() != null && result.body().get("message") != null
                    ? String.valueOf(result.body().get("message"))
                    : "正式保存行程失败，请稍后重试。";
            String errorCode = switch (result == null ? 500 : result.serviceStatus()) {
                case 401 -> "UNAUTHORIZED";
                case 403 -> "FORBIDDEN";
                case 404 -> "NOT_FOUND";
                case 409 -> "REVISION_CONFLICT";
                default -> "SAVE_TRIP_FAILED";
            };
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, errorCode, message);
        }

        Map<String, Object> body = result.body();
        Object savedTripObj = body.get("trip");
        Object resolvedTrip = savedTripObj;
        Object resolvedVersion = body.get("currentVersion");
        String resolvedTitle = null;

        if (!desiredTitle.isBlank() && savedTripObj instanceof Trip savedTrip
                && !desiredTitle.equals(savedTrip.title()) && tripApplicationService != null) {
            Map<String, Object> updateReq = new LinkedHashMap<>();
            updateReq.put("title", desiredTitle);
            updateReq.put("plan", savedTrip.plan());
            updateReq.put("expectedVersion", savedTrip.currentVersion());
            updateReq.put("idempotencyKey", "agent-save-title-" + UUID.randomUUID());
            updateReq.put("changeReason", "智能体按用户指定标题保存正式行程");
            TripService.OperationResult updated = tripApplicationService.updateTrip(
                    context.userId(), savedTrip.id(), updateReq);
            if (updated != null && updated.status() < 400 && updated.trip() != null) {
                resolvedTrip = updated.trip();
                resolvedVersion = updated.trip().currentVersion();
                resolvedTitle = updated.trip().title();
            }
        } else if (savedTripObj instanceof Trip savedTrip) {
            resolvedTitle = savedTrip.title();
        }

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("ok", true);
        receipt.put("saved", true);
        receipt.put("tripId", body.get("tripId"));
        receipt.put("currentVersion", resolvedVersion);
        if (resolvedTitle != null) receipt.put("title", resolvedTitle);
        if (resolvedTrip != null) receipt.put("trip", resolvedTrip);
        String summary = body.get("message") != null
                ? String.valueOf(body.get("message"))
                : "正式行程已保存成功（版本 v" + (resolvedVersion == null ? 1 : resolvedVersion) + "）。";
        if (resolvedTitle != null && !desiredTitle.isBlank() && resolvedTitle.equals(desiredTitle)) {
            summary = "正式行程《" + resolvedTitle + "》已保存成功（版本 v" + (resolvedVersion == null ? 1 : resolvedVersion) + "）。";
        }
        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, receipt), summary);
    }
}
