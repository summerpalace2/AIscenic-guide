package com.ai.guide.application.agent.tools;

import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiCandidate;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.geo.model.GeoRouteOutcome;
import com.ai.guide.domain.geo.model.GeoRoutePairOutcome;
import com.ai.guide.domain.geo.model.GeoRouteSegment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 真实通勤路线与耗时计算工具：封装高德路线计算网关。
 * 支持传入起点/终点名称或经纬度坐标，自动补齐坐标并返回步行及公交地铁耗时与距离。
 */
@Component
public class CalculateRouteMatrixTool implements AgentToolPort {

    public static final String TOOL_NAME = "calculate_route_matrix";
    private static final Pattern COORD_PATTERN = Pattern.compile("^-?\\d+(?:\\.\\d+)?,-?\\d+(?:\\.\\d+)?$");

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "origin": {
                  "type": "string",
                  "description": "起点地点名称或经纬度坐标（lng,lat 格式）"
                },
                "destination": {
                  "type": "string",
                  "description": "终点地点名称或经纬度坐标（lng,lat 格式）"
                },
                "city": {
                  "type": "string",
                  "description": "所属城市，默认使用当前上下文城市"
                },
                "preference": {
                  "type": "string",
                  "enum": ["transit", "walking"],
                  "description": "优先出行方式：transit(公交地铁，默认) 或 walking(步行)"
                }
              },
              "required": ["origin", "destination"]
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "计算两地之间真实的步行与公交地铁通行距离及耗时（严禁自行估算时间）。",
            SCHEMA,
            false
    );

    private final GeoRouteGatewayPort geoRouteGateway;
    private final AttractionService attractionService;
    private final ObjectMapper objectMapper;

    public CalculateRouteMatrixTool(GeoRouteGatewayPort geoRouteGateway,
                                    AttractionService attractionService,
                                    ObjectMapper objectMapper) {
        this.geoRouteGateway = geoRouteGateway;
        this.attractionService = attractionService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public AgentToolSpec spec() {
        return SPEC;
    }

    @Override
    public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        String origin = AgentToolJsonSupport.text(args, "origin", "");
        String destination = AgentToolJsonSupport.text(args, "destination", "");
        if (origin.isBlank() || destination.isBlank()) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "MISSING_ENDPOINTS",
                    "请同时提供起点 origin 与终点 destination");
        }
        String city = AgentToolJsonSupport.text(args, "city", context.activeCity());
        String preference = AgentToolJsonSupport.text(args, "preference", "transit");

        String originCoord = resolveCoordinate(origin, city);
        String destCoord = resolveCoordinate(destination, city);
        if (originCoord == null || destCoord == null) {
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "ENDPOINT_GEOCODE_FAILED",
                    "无法解析起点或终点的有效经纬度坐标（origin=" + origin + ", destination=" + destination + "）");
        }

        GeoRoutePairOutcome outcome = geoRouteGateway.routePair(
                originCoord, destCoord, city, origin, destination, preference);
        if (outcome == null || outcome.selected() == null || outcome.selected().status() != GeoOutcomeStatus.SUCCESS) {
            String reason = outcome != null && outcome.selected() != null && outcome.selected().reason() != null
                    ? outcome.selected().reason()
                    : "路线计算服务当前不可用或未查到有效路径";
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "ROUTE_UNAVAILABLE", reason);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("origin", origin);
        payload.put("destination", destination);
        payload.put("preference", outcome.preference());
        payload.put("walking", segmentSummary(outcome.walking()));
        payload.put("transit", segmentSummary(outcome.transit()));
        payload.put("selected", segmentSummary(outcome.selected()));

        GeoRouteSegment seg = outcome.selected().segment();
        String summary = seg == null ? "已计算路线"
                : origin + " → " + destination + " 约 " + Math.max(1, seg.durationSeconds() == null ? 0 : seg.durationSeconds() / 60) + " 分钟";
        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, payload), summary);
    }

    private String resolveCoordinate(String placeOrCoord, String city) {
        if (COORD_PATTERN.matcher(placeOrCoord).matches()) {
            return placeOrCoord;
        }
        if (attractionService != null) {
            List<Attraction> local = attractionService.list(city, null, null);
            if (local != null) {
                for (Attraction a : local) {
                    if (a.getLocation() != null && COORD_PATTERN.matcher(a.getLocation().trim()).matches()
                            && a.getName() != null && (a.getName().equalsIgnoreCase(placeOrCoord)
                            || a.getName().contains(placeOrCoord))) {
                        return a.getLocation().trim();
                    }
                }
            }
        }
        if (geoRouteGateway != null && geoRouteGateway.isConfigured()) {
            GeoPoiOutcome poi = geoRouteGateway.searchPoi(placeOrCoord, city);
            if (poi != null && poi.status() == GeoOutcomeStatus.SUCCESS && poi.candidates() != null) {
                for (GeoPoiCandidate c : poi.candidates()) {
                    if (c.coordinate() != null && c.coordinate().isValid()) {
                        return c.coordinate().longitude() + "," + c.coordinate().latitude();
                    }
                }
            }
        }
        return null;
    }

    private static Map<String, Object> segmentSummary(GeoRouteOutcome outcome) {
        if (outcome == null) {
            return Map.of("available", false);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("available", outcome.status() == GeoOutcomeStatus.SUCCESS && outcome.segment() != null);
        map.put("status", outcome.status() == null ? "UNKNOWN" : outcome.status().name());
        if (outcome.segment() != null) {
            GeoRouteSegment s = outcome.segment();
            map.put("mode", s.mode());
            map.put("distanceMeters", s.distanceMeters());
            map.put("durationSeconds", s.durationSeconds());
            map.put("durationMinutes", s.durationSeconds() == null ? null : Math.max(1, (int) Math.ceil(s.durationSeconds() / 60.0)));
            map.put("walkingDistanceMeters", s.walkingDistanceMeters());
        } else if (outcome.reason() != null) {
            map.put("reason", outcome.reason());
        }
        return map;
    }
}
