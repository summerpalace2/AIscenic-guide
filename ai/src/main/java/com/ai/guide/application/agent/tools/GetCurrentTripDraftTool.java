package com.ai.guide.application.agent.tools;

import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.planner.service.PlannerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前行程草稿只读查询工具：从规划会话读取精简的日程结构与乐观锁版本号。
 *
 * <p>严格遵循外部记忆精简原则：仅提取天数、站点名称、时段与版本信息，不将大体积路径折线坐标灌入模型上下文；
 * 若当前未绑定行程草稿，执行层返回 {@code NO_PLANNER_SESSION} 引导提示（保持工具全集恒定以保护 KV Cache）。
 */
@Component
public class GetCurrentTripDraftTool implements AgentToolPort {

    public static final String TOOL_NAME = "get_current_trip_draft";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "day": {
                  "type": "integer",
                  "description": "可选：只查看指定第几天（1 起计数）；留空返回全部天数摘要"
                }
              }
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "读取当前绑定的行程草稿各天站点安排、固定站点（pinnedStopIds）及当前版本号（currentVersion）。修改行程前应先调用此工具确认现状。",
            SCHEMA,
            false
    );

    private final PlanningApplicationService planningApplicationService;
    private final ObjectMapper objectMapper;

    public GetCurrentTripDraftTool(PlanningApplicationService planningApplicationService,
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
                    "当前未绑定行程草稿（plannerSessionId 为空）。请提示用户先生成或打开一份行程后，再查看或调整日程。");
        }
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        Integer filterDay = args.hasNonNull("day") ? args.get("day").asInt() : null;

        PlannerService.ServiceResult serviceResult =
                planningApplicationService.get(context.plannerSessionId(), context.plannerAccessToken());
        if (serviceResult == null || serviceResult.status() != 200 || serviceResult.body() == null) {
            String message = serviceResult != null && serviceResult.body() != null
                    && serviceResult.body().get("message") != null
                    ? String.valueOf(serviceResult.body().get("message"))
                    : "无法读取当前行程草稿，可能会话已失效或无权访问。";
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "TRIP_DRAFT_UNAVAILABLE", message);
        }

        Map<String, Object> body = serviceResult.body();
        Object rawTrip = body.get("trip");
        if (!(rawTrip instanceof Map<?, ?> tripMap)) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "TRIP_DRAFT_EMPTY", "当前规划会话尚未包含行程数据。");
        }

        Object currentVersion = body.get("currentVersion") != null ? body.get("currentVersion") : tripMap.get("version");
        List<Map<String, Object>> daysSummary = AgentToolJsonSupport.summarizeTripDays(tripMap.get("days"), filterDay);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("sessionId", context.plannerSessionId());
        payload.put("currentVersion", currentVersion);
        payload.put("title", tripMap.get("title"));
        payload.put("city", tripMap.get("city"));
        payload.put("pinnedStopIds", tripMap.get("pinnedStopIds") == null ? List.of() : tripMap.get("pinnedStopIds"));
        payload.put("days", daysSummary);

        String summary = "已读取行程草稿（v" + (currentVersion == null ? "?" : currentVersion)
                + "，共 " + daysSummary.size() + " 天摘要）";
        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, payload), summary);
    }
}
