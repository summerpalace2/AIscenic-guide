package com.ai.guide.application.agent.tools;

import com.ai.guide.application.agent.AgentTripAuthoringService;
import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行程调整确认应用工具（HIL 写操作工具：{@code requiresConfirmation = true}）。
 *
 * <p>在编排器循环中被强制拦截挂起，向前端发射 {@code confirm_request} 等待人工确认；
 * 仅当用户通过确认接口显式批准后，才调用 {@link AgentTripAuthoringService#applyPreviewedPatch} 完成 CAS 落库。
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
                "baseRevision": {
                  "type": "integer",
                  "description": "乐观锁版本号，须与预演时返回的 baseRevision 一致"
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
            "请求用户确认并正式应用已预演好的智能体行程调整方案（高风险写操作，触发人机确认卡片，不会静默写入）。",
            SCHEMA,
            true
    );

    private final AgentTripAuthoringService authoringService;
    private final ObjectMapper objectMapper;

    public ConfirmApplyTripAdjustmentTool(AgentTripAuthoringService authoringService, ObjectMapper objectMapper) {
        this.authoringService = authoringService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public AgentToolSpec spec() {
        return SPEC;
    }

    @Override
    public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        String proposalId = AgentToolJsonSupport.text(args, "proposalId", "");
        Integer baseRevision = args.hasNonNull("baseRevision")
                ? args.get("baseRevision").asInt(1)
                : context.baseRevision();

        AgentTripAuthoringService.ApplyPatchResult result = authoringService.applyPreviewedPatch(context, proposalId, baseRevision);
        if (!result.ok()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, result.errorCode(), result.message());
        }

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("ok", true);
        receipt.put("applied", true);
        receipt.put("sessionId", result.sessionId());
        receipt.put("currentVersion", result.currentVersion());
        receipt.put("changedSegments", result.changedSegments());
        receipt.put("message", result.message());
        receipt.put("trip", result.trip());
        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, receipt), result.message());
    }
}
