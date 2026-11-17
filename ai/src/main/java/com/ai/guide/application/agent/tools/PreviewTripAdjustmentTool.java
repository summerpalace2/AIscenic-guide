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
 * 全智能体自由局部/跨天行程调整预演工具（只读沙箱，零持久化副作用）。
 *
 * <p>接收智能体在读取当前草稿 {@code get_current_trip_draft} 后自由修订的 {@code days[]} 方案，
 * 在 {@link AgentTripAuthoringService} 中完成 POI 真实坐标核验、已固定（Pin）站点守卫与高德路线水化，
 * 生成包含 {@code proposalId} 与前后站点差异的预演提案；仅当随后调用 {@code confirm_apply_trip_adjustment} 并经人工批准后才提交修改。
 */
@Component
public class PreviewTripAdjustmentTool implements AgentToolPort {

    public static final String TOOL_NAME = "preview_trip_adjustment";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "changeSummary": {
                  "type": "string",
                  "description": "简明概括本次拟调整的内容，如“将第1天十八梯传统风貌区替换为湖广会馆”"
                },
                "baseRevision": {
                  "type": "integer",
                  "description": "当前草稿基线版本号（须与 get_current_trip_draft 一致）"
                },
                "operations": {
                  "type": "array",
                  "description": "【局部调整首选】仅传需增删改移的局部操作补丁，无需重写整份行程。单站替换/增删/调时优先用 operations。",
                  "items": {
                    "type": "object",
                    "properties": {
                      "op": { "type": "string", "enum": ["REPLACE_STOP", "REMOVE_STOP", "ADD_STOP", "MOVE_STOP", "UPDATE_STOP"] },
                      "day": { "type": "integer", "description": "目标所在第几天" },
                      "fromDay": { "type": "integer", "description": "MOVE_STOP 时源天" },
                      "toDay": { "type": "integer", "description": "MOVE_STOP 时目标天" },
                      "targetStopId": { "type": "string", "description": "待替换/删除/移动/更新的已有 stopId" },
                      "targetStopName": { "type": "string", "description": "待定位站点的已有名称" },
                      "insertIndex": { "type": "integer", "description": "ADD_STOP/MOVE_STOP 插入序号（从 0 开始）" },
                      "insertAfterStopName": { "type": "string", "description": "可选：插在某站点之后" },
                      "newStop": {
                        "type": "object",
                        "description": "REPLACE_STOP 或 ADD_STOP 时的新站点对象",
                        "properties": {
                          "name": { "type": "string" },
                          "type": { "type": "string", "enum": ["SCENIC", "DINING", "FREE_TIME"] },
                          "district": { "type": "string" },
                          "time": { "type": "string" },
                          "duration": { "type": "string" },
                          "summary": { "type": "string" },
                          "recommendationReason": { "type": "string" },
                          "poiId": { "type": "string" },
                          "venueId": { "type": "string" },
                          "location": { "type": "string" },
                          "openingHours": { "type": "string" }
                        },
                        "required": ["name", "type"]
                      }
                    },
                    "required": ["op"]
                  }
                },
                "title": {
                  "type": "string",
                  "description": "可选：若用户要求顺便修改行程标题"
                },
                "days": {
                  "type": "array",
                  "description": "【全局重构备选】仅在多天大改或重排整体天数时传完整 days 数组；局部调整请改传 operations。",
                  "items": {
                    "type": "object",
                    "properties": {
                      "day": { "type": "integer" },
                      "theme": { "type": "string" },
                      "agentNotes": { "type": "string" },
                      "stops": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "stopId": { "type": "string", "description": "保留未变或修改前原有站点的 stopId" },
                            "name": { "type": "string" },
                            "type": { "type": "string", "enum": ["SCENIC", "DINING", "FREE_TIME"] },
                            "district": { "type": "string" },
                            "time": { "type": "string" },
                            "duration": { "type": "string" },
                            "summary": { "type": "string" },
                            "recommendationReason": { "type": "string" },
                            "ticket": { "type": "string" },
                            "specialtyDish": { "type": "string" },
                            "openingHours": { "type": "string" },
                            "bookingPolicy": { "type": "string" },
                            "poiId": { "type": "string" },
                            "venueId": { "type": "string" },
                            "location": { "type": "string" }
                          },
                          "required": ["name", "type"]
                        }
                      }
                    },
                    "required": ["day", "stops"]
                  }
                }
              },
              "required": ["changeSummary"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "在只读沙箱中预演智能体提出的任意跨天/单站/餐饮/顺序修改方案，校验固定站点与高德路线可行性并返回 proposalId（不改写当前草稿）。",
            SCHEMA,
            false
    );

    private final AgentTripAuthoringService authoringService;
    private final ObjectMapper objectMapper;

    public PreviewTripAdjustmentTool(AgentTripAuthoringService authoringService, ObjectMapper objectMapper) {
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
        AgentTripAuthoringService.PreviewPatchResult result = authoringService.previewPatch(context, args);
        if (!result.ok()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, result.errorCode(), result.message());
        }

        Map<String, Object> concise = new LinkedHashMap<>();
        concise.put("ok", true);
        concise.put("canApply", true);
        concise.put("proposalId", result.proposalId());
        concise.put("baseRevision", result.baseRevision());
        concise.put("message", result.message());
        concise.put("stopChanges", result.stopChanges());
        concise.put("changedSegments", result.changedSegments());

        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, concise),
                "已生成调整预演：" + result.message());
    }
}
