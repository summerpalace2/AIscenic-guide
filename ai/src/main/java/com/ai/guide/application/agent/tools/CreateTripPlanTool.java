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
import java.util.List;
import java.util.Map;

/**
 * 全智能体原生多天行程编排工具（Agent-Native Draft Creation）。
 *
 * <p>由智能体先检索 POI、天气、路网耗时与个人偏好，再自行按天编排景点、餐饮与弹性时段，
 * 交由 {@link AgentTripAuthoringService} 完成地理坐标核验、高德路线水化与限量校核后落库；
 * 模型仅接收轻量日/站摘要，完整 {@code createdTrip} 经 {@code clientData} 同步前端画布展示。
 */
@Component
public class CreateTripPlanTool implements AgentToolPort {

    public static final String TOOL_NAME = "create_trip_plan";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "title": {
                  "type": "string",
                  "description": "本次行程标题，如“重庆亲子2日轻松游”"
                },
                "subtitle": {
                  "type": "string",
                  "description": "可选：一行副标题概括节奏、人群或主题"
                },
                "city": {
                  "type": "string",
                  "description": "目标城市，默认使用当前上下文城市"
                },
                "summary": {
                  "type": "string",
                  "description": "可选：整体行程安排说明"
                },
                "constraints": {
                  "type": "object",
                  "description": "可选：已确认的出行约束，如 companions、walkingTolerance、transportPreference、budget、dietPreference"
                },
                "days": {
                  "type": "array",
                  "description": "按天编排的行程列表（1~7 天）。每天必须严格尊重用户口头指定的站点所属天数与顺序。",
                  "items": {
                    "type": "object",
                    "properties": {
                      "day": { "type": "integer", "description": "第几天（从 1 开始）" },
                      "theme": { "type": "string", "description": "当天主题，如“母城地标与两江夜景”" },
                      "agentNotes": { "type": "string", "description": "可选：当天天气应对、避雨或节奏说明" },
                      "stops": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "name": { "type": "string", "description": "真实景点、餐厅或活动名称" },
                            "type": { "type": "string", "enum": ["SCENIC", "DINING", "FREE_TIME"], "description": "节点类型" },
                            "district": { "type": "string", "description": "所属片区/行政区" },
                            "time": { "type": "string", "description": "建议时段，如 09:30 / 12:00 午餐 / 18:30 夜游" },
                            "duration": { "type": "string", "description": "建议停留时长，如“约 90 分钟”" },
                            "summary": { "type": "string", "description": "简洁亮点介绍" },
                            "recommendationReason": { "type": "string", "description": "结合天气/偏好/路线的真实推荐依据" },
                            "ticket": { "type": "string", "description": "门票或人均参考（若未查证写待确认）" },
                            "specialtyDish": { "type": "string", "description": "餐饮类必尝特色菜" },
                            "openingHours": { "type": "string", "description": "已核验开放时间" },
                            "bookingPolicy": { "type": "string", "description": "预约/限流提示" },
                            "poiId": { "type": "string", "description": "建议填写 search_poi_and_dining 返回的 poiId" },
                            "venueId": { "type": "string", "description": "可选本地目录 id" },
                            "location": { "type": "string", "description": "建议填写已查证经纬度 lng,lat" }
                          },
                          "required": ["name", "type"]
                        }
                      }
                    },
                    "required": ["day", "theme", "stops"]
                  }
                }
              },
              "required": ["title", "days"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "将智能体自主编排的多天行程草案（含每日景点、餐饮、理由与核验 POI 坐标）经过高德实景核验与路线水化后落库为正式可编辑草稿。",
            SCHEMA,
            false
    );

    private final AgentTripAuthoringService authoringService;
    private final ObjectMapper objectMapper;

    public CreateTripPlanTool(AgentTripAuthoringService authoringService, ObjectMapper objectMapper) {
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
        AgentTripAuthoringService.CreateDraftResult result = authoringService.createDraft(context, args);
        if (!result.ok()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, result.errorCode(), result.message());
        }

        Map<String, Object> tripMap = result.trip() == null ? Map.of() : result.trip();
        List<Map<String, Object>> daysSummary = AgentToolJsonSupport.summarizeTripDays(tripMap.get("days"), null);

        Map<String, Object> llmPayload = new LinkedHashMap<>();
        llmPayload.put("ok", true);
        llmPayload.put("plannerSessionId", result.sessionId());
        llmPayload.put("currentVersion", result.currentVersion());
        llmPayload.put("title", tripMap.get("title"));
        llmPayload.put("city", tripMap.get("city"));
        llmPayload.put("sourceMode", tripMap.get("sourceMode"));
        llmPayload.put("days", daysSummary);

        Map<String, Object> clientData = new LinkedHashMap<>();
        clientData.put("plannerSessionId", result.sessionId());
        clientData.put("currentVersion", result.currentVersion());
        clientData.put("createdTrip", tripMap);

        String summary = "已按您的要求编排并核验生成新行程（" + daysSummary.size() + " 天，v" + result.currentVersion() + "）";
        return AgentToolResult.successWithClientData(
                TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, llmPayload), summary, clientData);
    }
}
