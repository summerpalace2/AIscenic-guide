package com.ai.guide.application.attraction;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionMediaService;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService;
import com.ai.guide.domain.rag.service.ProductionV2FactQueryService;
import com.ai.guide.domain.trip.service.TripPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("AttractionApplicationServiceImpl 应用服务测试")
class AttractionApplicationServiceImplTest {

    private final AttractionService attractionService = mock(AttractionService.class);
    private final AttractionMediaService attractionMediaService = mock(AttractionMediaService.class);
    private final TripPlanService tripPlanService = mock(TripPlanService.class);
    private final ProductionV2FactQueryService productionV2FactQueryService = mock(ProductionV2FactQueryService.class);
    private final RuntimeAttractionDetailService runtimeAttractionDetailService = mock(RuntimeAttractionDetailService.class);

    private final AttractionApplicationService service = new AttractionApplicationServiceImpl(
            attractionService,
            attractionMediaService,
            tripPlanService,
            productionV2FactQueryService,
            runtimeAttractionDetailService
    );

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("list - 成功查询景点列表并包装为 Result")
    void testList() {
        Attraction item = new Attraction();
        item.setId("cq-hongyadong");
        when(attractionService.list("渝中区", "核心地标")).thenReturn(List.of(item));

        Result<List<Attraction>> result = service.list("渝中区", "核心地标");
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
        assertEquals(1, result.getData().size());
        assertEquals("cq-hongyadong", result.getData().get(0).getId());
    }

    @Test
    @DisplayName("get - 普通地标存在时进行富媒体装配并返回 200")
    void testGetNormalSuccess() {
        Attraction item = new Attraction();
        item.setId("cq-hongyadong");
        when(attractionService.get("cq-hongyadong")).thenReturn(item);
        when(attractionMediaService.enrich(item)).thenReturn(item);

        Result<?> result = service.get("cq-hongyadong");
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
        assertEquals(item, result.getData());
        verify(attractionMediaService).enrich(item);
    }

    @Test
    @DisplayName("get - 普通地标不存在时返回 404 景点不存在")
    void testGetNormalNotFound() {
        when(attractionService.get("unknown")).thenReturn(null);

        Result<?> result = service.get("unknown");
        assertNotNull(result);
        assertEquals(404, result.getCode());
        assertEquals("景点不存在", result.getMessage());
        assertNull(result.getData());
    }

    @Test
    @DisplayName("get - 动态景点 (amap-/runtime-stop-) 存在时返回 200，缺失时返回 404")
    void testGetDynamic() {
        // 1. amap- 存在
        Map<String, Object> amapDetail = Map.of("name", "动态小面馆");
        when(runtimeAttractionDetailService.detail("amap-123")).thenReturn(amapDetail);

        Result<?> result1 = service.get("amap-123");
        assertEquals(200, result1.getCode());
        assertEquals("查询成功", result1.getMessage());
        assertEquals(amapDetail, result1.getData());

        // 2. runtime-stop- 不存在
        when(runtimeAttractionDetailService.detail("runtime-stop-999")).thenReturn(null);
        Result<?> result2 = service.get("runtime-stop-999");
        assertEquals(404, result2.getCode());
        assertEquals("高德未返回该动态景点详情", result2.getMessage());
    }

    @Test
    @DisplayName("getFacts - ProductionV2 事实库命中直接返回 200")
    void testGetFactsProductionV2Hit() {
        Map<String, Object> facts = Map.of("venueId", "cq-jiefangbei", "provenance", "PRODUCTION_V2");
        when(productionV2FactQueryService.detail("cq-jiefangbei")).thenReturn(facts);

        Result<Map<String, Object>> result = service.getFacts("cq-jiefangbei");
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
        assertEquals(facts, result.getData());
        verifyNoInteractions(tripPlanService);
    }

    @Test
    @DisplayName("getFacts - ProductionV2 未命中且已登录用户回退到行程规划投影")
    void testGetFactsTripFallbackForLoggedInUser() {
        UserContext.set("user-001", "testuser", "USER");
        when(productionV2FactQueryService.detail("cq-custom-spot")).thenReturn(null);

        Map<String, Object> tripFact = Map.of("venueId", "cq-custom-spot", "source", "USER_TRIP");
        when(tripPlanService.findAttraction(eq("user-001"), eq("cq-custom-spot"))).thenReturn(tripFact);

        Result<Map<String, Object>> result = service.getFacts("cq-custom-spot");
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
        assertEquals(tripFact, result.getData());
    }

    @Test
    @DisplayName("getFacts - 匿名用户或事实均未命中时返回 404 暂无可核验的景点详情")
    void testGetFactsNotFound() {
        UserContext.clear(); // 匿名
        when(productionV2FactQueryService.detail("unknown-id")).thenReturn(null);

        Result<Map<String, Object>> result = service.getFacts("unknown-id");
        assertEquals(404, result.getCode());
        assertEquals("暂无可核验的景点详情", result.getMessage());
        verifyNoInteractions(tripPlanService);
    }

    @Test
    @DisplayName("getFacts - 动态景点 (amap-/runtime-stop-) 存在时返回 200，缺失时返回 404")
    void testGetFactsDynamic() {
        // 1. amap- 存在
        Map<String, Object> amapDetail = Map.of("name", "动态小面馆", "rating", "4.8");
        when(runtimeAttractionDetailService.detail("amap-456")).thenReturn(amapDetail);

        Result<Map<String, Object>> result1 = service.getFacts("amap-456");
        assertEquals(200, result1.getCode());
        assertEquals("查询成功", result1.getMessage());
        assertEquals(amapDetail, result1.getData());

        // 2. runtime-stop- 不存在
        when(runtimeAttractionDetailService.detail("runtime-stop-888")).thenReturn(null);
        Result<Map<String, Object>> result2 = service.getFacts("runtime-stop-888");
        assertEquals(404, result2.getCode());
        assertEquals("高德未返回该动态景点详情", result2.getMessage());
    }
}
