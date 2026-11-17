package com.ai.guide.application.agent.tools;

import com.ai.guide.application.planner.ApplyAdjustmentResult;
import com.ai.guide.application.planner.PlanningActor;
import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行程调整确认应用工具（HIL 写操作工具：{@code requiresConfirmation = true}）。
 *
 * <p>在编排器循环中被强制拦截挂起，向前端发射 {@code confirm_request} 等待人工确认；
 * 仅当用户通过确认接口显式批准后，才会在受控沙箱中调用 {@link PlanningApplicationService#applyAdjustment} 完成版本 CAS 落库。
 */
@Component
public class ConfirmApplyTripAdjustmentTool implements AgentToolPort {

    public static final String TOOL_NAME = "confirm_apply_trip_adjustment";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "proposalId": {
                  "type": "string",
                  "description": "由 preview_trip_adjustment 返回的调整提案 ID"
                },
                "optionId": {
                  "type": "string",
                  "description": "选定的方案 optionId；若预演仅包含单个主方案可传对应默认 optionId"
                },
                "baseRevision": {
                  "type": "integer",
                  "description": "乐观锁版本号，须与预演时返回的 baseRevision 一致"
                },
                "forceApply": {
                  "type": "boolean",
                  "description": "当用户明确已知顺路警告并坚持应用时为 true，默认为 false"
                },
                "changeSummary": {
                  "type": "string",
                  "description": "向用户确认卡片展示的一句话中文变更说明"
                }
              },
              "required": ["proposalId", "baseRevision", "changeSummary"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "请求用户确认并正式应用已预演好的行程调整方案（高风险写操作，触发人机确认卡片，不会静默写入）。",
            SCHEMA,
            true
    );

    private final PlanningApplicationService planningApplicationService;
    private final ObjectMapper objectMapper;

    public ConfirmApplyTripAdjustmentTool(PlanningApplicationService planningApplicationService,
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
                    "当前未绑定行程草稿，无法应用行程调整。");
        }
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        String proposalId = AgentToolJsonSupport.text(args, "proposalId", "");
        if (proposalId.isBlank()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "MISSING_PROPOSAL_ID",
                    "缺少调整提案标识 proposalId，请先通过预演生成有效方案。");
        }
        String optionId = AgentToolJsonSupport.text(args, "optionId", "");
        int baseRevision = args.hasNonNull("baseRevision")
                ? args.get("baseRevision").asInt(1)
                : (context.baseRevision() != null ? context.baseRevision() : 1);
        boolean forceApply = args.hasNonNull("forceApply") && args.get("forceApply").asBoolean(false);

        ApplyAdjustmentRequestDto dto = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId(optionId.isBlank() ? null : optionId)
                .baseRevision(baseRevision)
                .forceApply(forceApply)
                .sessionAccessToken(context.plannerAccessToken())
                .idempotencyKey("agent-" + proposalId + "-" + (optionId.isBlank() ? "default" : optionId))
                .build();

        PlanningActor actor = new PlanningActor(
                context.userId().isBlank() ? null : context.userId(),
                context.anonymous()
        );
        ApplyAdjustmentResult result = planningApplicationService.applyAdjustment(actor, context.plannerSessionId(), dto);
        if (result == null || result.body() == null) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "APPLY_FAILED", "应用调整未返回有效回执。");
        }
        Map<String, Object> body = result.body();
        if (result.outcome() != ApplyAdjustmentResult.Outcome.SUCCESS) {
            String errorCode = switch (result.outcome()) {
                case CONFLICT -> "REVISION_CONFLICT";
                case NOT_FOUND -> "NOT_FOUND";
                case UNAUTHORIZED -> "UNAUTHORIZED";
                case FORBIDDEN -> "FORBIDDEN";
                default -> "APPLY_REJECTED";
            };
            String message = body.get("message") == null ? "行程调整应用失败" : String.valueOf(body.get("message"));
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, errorCode, message);
        }

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("ok", true);
        receipt.put("applied", true);
        receipt.put("sessionId", body.get("sessionId"));
        receipt.put("currentVersion", body.get("currentVersion"));
        receipt.put("changedSegments", body.get("changedSegments"));
        receipt.put("message", body.get("message"));
        receipt.put("trip", body.get("trip"));
        String summary = body.get("message") != null ? String.valueOf(body.get("message")) : "行程调整已应用。";
        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, receipt), summary);
    }
}
