package com.ai.guide.application.agent.tools;

import com.ai.guide.application.chat.ChatRetrievalCoordinator;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoCoordinate;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiCandidate;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.geo.model.GeoRouteOutcome;
import com.ai.guide.domain.geo.model.GeoRoutePairOutcome;
import com.ai.guide.domain.geo.model.GeoRouteSegment;
import com.ai.guide.domain.geo.model.GeoWeatherCast;
import com.ai.guide.domain.geo.model.GeoWeatherForecast;
import com.ai.guide.domain.geo.model.GeoWeatherOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 4 个只读探索工具（天气、POI 检索、路线计算、知识库 RAG）的单元测试。
 */
class AgentReadOnlyToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AgentExecutionContext DEFAULT_CTX =
            new AgentExecutionContext("u1", "s1", "", "", "重庆", null, "", "", false);

    @Test
    void queryWeatherToolReturnsForecastAndHandlesUnavailable() throws Exception {
        GeoRouteGatewayPort geo = mock(GeoRouteGatewayPort.class);
        when(geo.weather("重庆")).thenReturn(new GeoWeatherOutcome(
                GeoOutcomeStatus.SUCCESS,
                new GeoWeatherForecast("2026-08-12 08:00",
                        List.of(new GeoWeatherCast("2026-08-12", "小雨", "阴", "29", "23")), true),
                null,
                Instant.now()
        ));

        QueryWeatherTool tool = new QueryWeatherTool(geo, MAPPER);
        AgentToolResult ok = tool.execute("{}", DEFAULT_CTX);
        assertTrue(ok.ok());
        assertTrue(ok.summary().contains("重庆"));
        assertTrue(ok.summary().contains("小雨"));
        JsonNode okJson = MAPPER.readTree(ok.jsonPayload());
        assertTrue(okJson.path("ok").asBoolean());
        assertEquals(1, okJson.path("casts").size());

        when(geo.weather("成都")).thenReturn(new GeoWeatherOutcome(
                GeoOutcomeStatus.UNSUPPORTED, null, "AMAP_KEY missing", Instant.now()));
        AgentToolResult fail = tool.execute("{\"city\":\"成都\"}", DEFAULT_CTX);
        assertFalse(fail.ok());
        assertTrue(fail.jsonPayload().contains("WEATHER_UNAVAILABLE"));
    }

    @Test
    void searchPoiAndDiningToolMergesSourcesAndRespectsCategory() throws Exception {
        AttractionService attractionService = mock(AttractionService.class);
        DiningVenueService diningService = mock(DiningVenueService.class);
        GeoRouteGatewayPort geo = mock(GeoRouteGatewayPort.class);

        when(attractionService.list("重庆", null, null)).thenReturn(List.of(
                Attraction.builder().id("s1").name("重庆中国三峡博物馆").district("渝中区")
                        .category("室内博物馆").location("106.550,29.562").openingHours("09:00-17:00").build()
        ));
        when(diningService.listAll()).thenReturn(List.of(
                DiningVenue.builder().id("d1").name("老院子清汤鱼").district("渝中区")
                        .category("江湖菜").spicinessLevel("不辣").specialtyDish("酸萝卜老鸭汤").location("106.555,29.560").build()
        ));
        when(geo.isConfigured()).thenReturn(true);
        when(geo.searchPoi(anyString(), anyString(), anyString())).thenReturn(new GeoPoiOutcome(
                GeoOutcomeStatus.SUCCESS,
                List.of(new GeoPoiCandidate("p1", "三峡博物馆东门", new GeoCoordinate(106.551, 29.562),
                        "人民路236号", "科教文化", null, null, null, null, null, 4.9, null)),
                null,
                Instant.now()
        ));

        SearchPoiAndDiningTool tool = new SearchPoiAndDiningTool(attractionService, diningService, geo, MAPPER);

        // 缺少关键词时返回结构化错误
        AgentToolResult missing = tool.execute("{}", DEFAULT_CTX);
        assertFalse(missing.ok());
        assertTrue(missing.jsonPayload().contains("MISSING_QUERY"));

        // 指定 SCENIC 类别时不查餐饮、保留本地景点与高德景点
        AgentToolResult scenic = tool.execute("{\"query\":\"博物馆\",\"category\":\"SCENIC\"}", DEFAULT_CTX);
        assertTrue(scenic.ok());
        JsonNode scenicJson = MAPPER.readTree(scenic.jsonPayload());
        assertEquals(1, scenicJson.path("localScenic").size());
        assertEquals(0, scenicJson.path("localDining").size());
        assertEquals(1, scenicJson.path("livePois").size());

        // 无候选结果时返回结构化 failure 供模型换词重查
        when(geo.searchPoi(anyString(), anyString())).thenReturn(
                new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "empty", Instant.now()));
        AgentToolResult empty = tool.execute("{\"query\":\"不存在的地址XYZ\"}", DEFAULT_CTX);
        assertFalse(empty.ok());
        assertTrue(empty.jsonPayload().contains("NO_POI_FOUND"));
    }

    @Test
    void calculateRouteMatrixToolResolvesEndpointsAndReturnsDurations() throws Exception {
        GeoRouteGatewayPort geo = mock(GeoRouteGatewayPort.class);
        AttractionService attractionService = mock(AttractionService.class);

        when(attractionService.list("重庆", null, null)).thenReturn(List.of(
                Attraction.builder().id("jfb").name("解放碑").location("106.577,29.558").build()
        ));
        when(geo.isConfigured()).thenReturn(true);
        when(geo.searchPoi("三峡博物馆", "重庆")).thenReturn(new GeoPoiOutcome(
                GeoOutcomeStatus.SUCCESS,
                List.of(new GeoPoiCandidate("p-sxb", "重庆中国三峡博物馆", new GeoCoordinate(106.550, 29.562),
                        "人民路236号", "博物馆", null, null, null, null, null)),
                null,
                Instant.now()
        ));
        GeoRouteOutcome walk = new GeoRouteOutcome(GeoOutcomeStatus.SUCCESS,
                new GeoRouteSegment("walking", 3200, 2700, 3200, 4, List.of(), null, true),
                null, "walking", Instant.now());
        GeoRouteOutcome transit = new GeoRouteOutcome(GeoOutcomeStatus.SUCCESS,
                new GeoRouteSegment("transit", 3500, 1080, 450, 2, List.of(), null, true),
                null, "transit", Instant.now());
        when(geo.routePair("106.577,29.558", "106.55,29.562", "重庆", "解放碑", "三峡博物馆", "transit"))
                .thenReturn(new GeoRoutePairOutcome(walk, transit, transit, "transit", false));

        CalculateRouteMatrixTool tool = new CalculateRouteMatrixTool(geo, attractionService, MAPPER);
        AgentToolResult ok = tool.execute("{\"origin\":\"解放碑\",\"destination\":\"三峡博物馆\"}", DEFAULT_CTX);
        assertTrue(ok.ok());
        assertTrue(ok.summary().contains("18 分钟"));
        JsonNode json = MAPPER.readTree(ok.jsonPayload());
        assertEquals(18, json.path("selected").path("durationMinutes").asInt());
        assertEquals(45, json.path("walking").path("durationMinutes").asInt());

        // 起点或终点缺失时报错
        assertFalse(tool.execute("{\"origin\":\"解放碑\"}", DEFAULT_CTX).ok());

        // 无法地理编码终点时返回 ENDPOINT_GEOCODE_FAILED
        when(geo.searchPoi("火星站", "重庆")).thenReturn(
                new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "not found", Instant.now()));
        AgentToolResult geocodeFail = tool.execute("{\"origin\":\"106.577,29.558\",\"destination\":\"火星站\"}", DEFAULT_CTX);
        assertFalse(geocodeFail.ok());
        assertTrue(geocodeFail.jsonPayload().contains("ENDPOINT_GEOCODE_FAILED"));
    }

    @Test
    void searchTravelKnowledgeToolPropagatesCitationsAndHandlesUnavailable() throws Exception {
        ChatRetrievalCoordinator coordinator = mock(ChatRetrievalCoordinator.class);
        when(coordinator.retrieveChat(anyString(), anyBoolean(), anyString())).thenReturn(
                new ChatRetrievalCoordinator.ChatRetrieval(
                        "三峡博物馆每周一闭馆，需提前免费预约。",
                        "已核验",
                        "qdrant",
                        null,
                        true,
                        List.of(Map.of("title", "三峡博物馆开放公告", "source", "official"))
                )
        );

        SearchTravelKnowledgeTool tool = new SearchTravelKnowledgeTool(coordinator, MAPPER);
        AgentToolResult ok = tool.execute("{\"query\":\"三峡博物馆周一开吗\",\"deep\":true}", DEFAULT_CTX);
        assertTrue(ok.ok());
        assertEquals(1, ok.citations().size());
        verify(coordinator).retrieveChat("三峡博物馆周一开吗", true, "重庆");
        JsonNode okJson = MAPPER.readTree(ok.jsonPayload());
        assertTrue(okJson.path("verified").asBoolean());

        when(coordinator.retrieveChat("离线测试", false, "重庆")).thenReturn(
                new ChatRetrievalCoordinator.ChatRetrieval("", "不可用", "none", "Qdrant offline", false, List.of())
        );
        AgentToolResult unavailable = tool.execute("{\"query\":\"离线测试\"}", DEFAULT_CTX);
        assertFalse(unavailable.ok());
        assertTrue(unavailable.summary().contains("不可用"));
    }
}
