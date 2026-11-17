package com.ai.guide.application.agent.tools;

import com.ai.guide.application.planner.PlanningActor;
import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.application.planner.PreviewAdjustmentResult;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.ai.guide.domain.planner.service.PlannerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性行程调整预演工具（只读沙箱，零副作用）。
 *
 * <p>将自然语言调整指令交由 {@link PlanningApplicationService#previewAdjustment} 在沙箱内运行
 * RouteAwarePlanner / PlanVerifier，生成包含 {@code proposalId}、候选方案、可行性与路线变更的预览结果。
 * 绝不直接修改正式或草稿行程，真实失败原因（如闭馆、跨午夜超时、版本冲突）原样返回给模型自纠。
 */
@Component
public class PreviewTripAdjustmentTool implements AgentToolPort {

    public static final String TOOL_NAME = "preview_trip_adjustment";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "instruction": {
                  "type": "string",
                  "description": "用户的明确行程调整指令，如“把第2天下午的南山一棵树换成重庆中国三峡博物馆”或“第1天晚上加一家不辣江湖菜”"
                },
                "activeDay": {
                  "type": "integer",
                  "description": "目标调整天数（1 起计数），留空则优先沿用上下文焦点天"
                },
                "targetStopId": {
                  "type": "string",
                  "description": "可选：目标站点 ID，留空则沿用当前选中站点"
                },
                "baseRevision": {
                  "type": "integer",
                  "description": "可选：当前行程版本号（用于 CAS 校验），留空则自动对齐上下文或当前草稿版本"
                }
              },
              "required": ["instruction"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "在确定性规划沙箱中预演行程调整（不会修改当前行程），返回 proposalId、可选 optionId 列表与可行性校验结果。仅在已有行程调整需求时调用。",
            SCHEMA,
            false
    );

    private final PlanningApplicationService planningApplicationService;
    private final ObjectMapper objectMapper;

    public PreviewTripAdjustmentTool(PlanningApplicationService planningApplicationService,
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
                    "当前未绑定行程草稿，无法执行调整预演。请提示用户先生成或打开一份行程。");
        }
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        String instruction = AgentToolJsonSupport.text(args, "instruction", "");
        if (instruction.isBlank()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "MISSING_INSTRUCTION",
                    "请提供明确的行程调整指令 instruction。");
        }

        Integer activeDay = args.hasNonNull("activeDay") ? args.get("activeDay").asInt() : context.activeDay();
        String targetStopId = AgentToolJsonSupport.text(args, "targetStopId", context.activeStopId());
        int baseRevision = resolveBaseRevision(args, context);

        PlanPageContext pageContext = PlanPageContext.builder()
                .activeDay(activeDay)
                .selectedStopId(targetStopId == null || targetStopId.isBlank() ? null : targetStopId)
                .build();

        PlanConversationRequestDto dto = PlanConversationRequestDto.builder()
                .sessionId(context.plannerSessionId())
                .baseRevision(baseRevision)
                .message(instruction)
                .context(pageContext)
                .sessionAccessToken(context.plannerAccessToken())
                .build();

        PlanningActor actor = new PlanningActor(
                context.userId().isBlank() ? null : context.userId(),
                context.anonymous()
        );
        PreviewAdjustmentResult result = planningApplicationService.previewAdjustment(actor, context.plannerSessionId(), dto);
        if (result == null || result.body() == null) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "PREVIEW_EMPTY", "预演引擎未返回有效结果。");
        }

        Map<String, Object> body = result.body();
        if (result.outcome() != PreviewAdjustmentResult.Outcome.SUCCESS) {
            String message = body.get("message") == null ? "预演未能生成可应用方案" : String.valueOf(body.get("message"));
            Map<String, Object> failurePayload = new LinkedHashMap<>();
            failurePayload.put("ok", false);
            failurePayload.put("outcome", result.outcome().name());
            failurePayload.put("message", message);
            if (body.get("reasonCodes") != null) failurePayload.put("reasonCodes", body.get("reasonCodes"));
            if (body.get("currentVersion") != null) failurePayload.put("currentVersion", body.get("currentVersion"));
            return AgentToolResult.failure(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, failurePayload),
                    "预演未通过：" + message);
        }

        Map<String, Object> concise = new LinkedHashMap<>();
        concise.put("ok", true);
        concise.put("proposalId", body.get("proposalId"));
        concise.put("baseRevision", body.get("baseRevision") != null ? body.get("baseRevision") : baseRevision);
        concise.put("feasible", body.get("feasible"));
        concise.put("canApply", body.get("canApply"));
        concise.put("message", body.get("message"));
        concise.put("changedSegments", body.get("changedSegments") == null ? List.of() : body.get("changedSegments"));
        concise.put("reasonCodes", body.get("reasonCodes") == null ? List.of() : body.get("reasonCodes"));
        if (body.get("stopChanges") != null) concise.put("stopChanges", body.get("stopChanges"));
        if (body.get("optionStopChanges") != null) concise.put("optionStopChanges", body.get("optionStopChanges"));
        if (body.get("optionStatus") != null) concise.put("optionStatus", body.get("optionStatus"));
        if (body.get("candidateReplacements") != null) concise.put("candidateReplacements", body.get("candidateReplacements"));

        String summary = body.get("message") != null ? String.valueOf(body.get("message")) : "已生成调整方案预览";
        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, concise), summary);
    }

    private int resolveBaseRevision(JsonNode args, AgentExecutionContext context) {
        if (args.hasNonNull("baseRevision")) {
            return args.get("baseRevision").asInt(1);
        }
        if (context.baseRevision() != null && context.baseRevision() > 0) {
            return context.baseRevision();
        }
        PlannerService.ServiceResult draft = planningApplicationService.get(context.plannerSessionId(), context.plannerAccessToken());
        if (draft != null && draft.status() == 200 && draft.body() != null) {
            Object v = draft.body().get("currentVersion");
            if (v instanceof Number num) {
                return num.intValue();
            }
        }
        return 1;
    }
}
