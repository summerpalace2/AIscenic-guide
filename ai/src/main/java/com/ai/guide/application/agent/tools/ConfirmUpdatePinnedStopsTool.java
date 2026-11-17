package com.ai.guide.application.agent.tools;

import com.ai.guide.application.planner.PlanningActor;
import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.planner.model.PlannerPinsRequest;
import com.ai.guide.domain.planner.service.PlannerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行程必去站点固定/解锁工具（HIL 写操作工具：{@code requiresConfirmation = true}）。
 *
 * <p>固定后的站点在后续局部重排时受核心约束保护，不会被替换或挤出。
 * 执行写操作前必须先经用户确认，并校验 CAS 期望版本 {@code expectedVersion}。
 */
@Component
public class ConfirmUpdatePinnedStopsTool implements AgentToolPort {

    public static final String TOOL_NAME = "confirm_update_pinned_stops";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "addStopIds": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "可选：要新增固定的站点 stopId 列表（需先调用 get_current_trip_draft 获取准确 stopId）"
                },
                "removeStopIds": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "可选：要取消固定的站点 stopId 列表"
                },
                "expectedVersion": {
                  "type": "integer",
                  "description": "当前行程乐观锁版本号，须与当前草稿一致"
                },
                "changeSummary": {
                  "type": "string",
                  "description": "向用户确认卡片展示的一句话中文固定说明"
                }
              },
              "required": ["changeSummary"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "请求用户确认并固定或取消固定行程中的关键景点（触发 HIL 确认，不会静默写入）。",
            SCHEMA,
            true
    );

    private final PlanningApplicationService planningApplicationService;
    private final ObjectMapper objectMapper;

    public ConfirmUpdatePinnedStopsTool(PlanningApplicationService planningApplicationService,
                                        ObjectMapper objectMapper) {
        this.planningApplicationService = planningApplicationService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public AgentToolSpec spec() {
        return SPEC;
    }

    @Override
    public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
        if (context == null || !context.hasPlannerSession()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "NO_PLANNER_SESSION",
                    "当前未绑定行程草稿，无法更新固定站点。");
        }
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        List<String> addIds = readStringList(args, "addStopIds");
        List<String> removeIds = readStringList(args, "removeStopIds");
        if (addIds.isEmpty() && removeIds.isEmpty()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "MISSING_STOP_IDS",
                    "请至少提供一个要固定（addStopIds）或取消固定（removeStopIds）的站点 stopId。");
        }
        Integer expectedVersion = args.hasNonNull("expectedVersion")
                ? args.get("expectedVersion").asInt()
                : context.baseRevision();

        PlanningActor actor = new PlanningActor(
                context.userId().isBlank() ? null : context.userId(),
                context.anonymous()
        );
        PlannerPinsRequest pinsRequest = new PlannerPinsRequest(
                expectedVersion, addIds, removeIds, context.plannerAccessToken());

        PlannerService.ServiceResult result =
                planningApplicationService.updatePins(actor, context.plannerSessionId(), pinsRequest);
        if (result == null || result.body() == null || result.status() >= 400) {
            String message = result != null && result.body() != null && result.body().get("message") != null
                    ? String.valueOf(result.body().get("message"))
                    : "更新固定站点失败。";
            String errorCode = switch (result == null ? 500 : result.status()) {
                case 409 -> "REVISION_CONFLICT";
                case 404 -> "NOT_FOUND";
                case 401 -> "UNAUTHORIZED";
                default -> "UPDATE_PINS_REJECTED";
            };
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, errorCode, message);
        }

        Map<String, Object> body = result.body();
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("ok", true);
        receipt.put("applied", true);
        receipt.put("sessionId", body.get("sessionId"));
        receipt.put("currentVersion", body.get("currentVersion"));
        receipt.put("pinnedStopIds", body.get("pinnedStopIds"));
        receipt.put("trip", body.get("trip"));

        String summary = "已更新固定站点设置（v" + body.getOrDefault("currentVersion", "?") + "）";
        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, receipt), summary);
    }

    private static List<String> readStringList(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field) || !args.get(field).isArray()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (JsonNode n : args.get(field)) {
            String v = n.asText("").trim();
            if (!v.isEmpty()) list.add(v);
        }
        return list;
    }
}
