package com.ai.guide.application.agent.tools;

import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoWeatherForecast;
import com.ai.guide.domain.geo.model.GeoWeatherOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 天气查询工具：封装高德天气网关，供智能体在规划或推荐室内/户外活动前实时查证气象。
 */
@Component
public class QueryWeatherTool implements AgentToolPort {

    public static final String TOOL_NAME = "query_weather_forecast";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "city": {
                  "type": "string",
                  "description": "城市或区县名称/adcode（如：重庆、渝中区、南岸区）；留空时默认使用当前上下文城市"
                }
              }
            }
            """;

    private static final AgentToolSpec SPEC = new AgentToolSpec(
            TOOL_NAME,
            "查询指定城市或区县的未来天气预报（天气现象、白天/夜间温度）。适合在安排户外/室内景点或雨天备选方案前调用。",
            SCHEMA,
            false
    );

    private final GeoRouteGatewayPort geoRouteGateway;
    private final ObjectMapper objectMapper;

    public QueryWeatherTool(GeoRouteGatewayPort geoRouteGateway, ObjectMapper objectMapper) {
        this.geoRouteGateway = geoRouteGateway;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    @Override
    public AgentToolSpec spec() {
        return SPEC;
    }

    @Override
    public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
        JsonNode args = AgentToolJsonSupport.parseArgs(objectMapper, argumentsJson);
        String city = AgentToolJsonSupport.text(args, "city", context.activeCity());
        GeoWeatherOutcome outcome = geoRouteGateway.weather(city);
        if (outcome == null || outcome.status() != GeoOutcomeStatus.SUCCESS
                || outcome.weather() == null || !outcome.weather().available()) {
            String reason = outcome == null || outcome.reason() == null || outcome.reason().isBlank()
                    ? "天气服务当前不可用或未配置密钥"
                    : outcome.reason();
            return AgentToolJsonSupport.failure(objectMapper, TOOL_NAME, "WEATHER_UNAVAILABLE", reason);
        }
        GeoWeatherForecast forecast = outcome.weather();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", true);
        payload.put("city", city);
        payload.put("reportTime", forecast.reportTime());
        payload.put("casts", forecast.casts());
        String firstDay = forecast.casts().isEmpty() ? "已返回天气预报"
                : forecast.casts().get(0).date() + " " + forecast.casts().get(0).dayWeather();
        return AgentToolResult.success(TOOL_NAME, AgentToolJsonSupport.writeJson(objectMapper, payload),
                "已查询" + city + "天气：" + firstDay);
    }
}
