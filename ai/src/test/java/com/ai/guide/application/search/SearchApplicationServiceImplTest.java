package com.ai.guide.application.search;

import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.geo.model.GeoRouteOutcome;
import com.ai.guide.domain.geo.model.GeoRoutePairOutcome;
import com.ai.guide.domain.geo.model.GeoWeatherOutcome;

import com.ai.guide.common.model.Result;
import com.ai.guide.infrastructure.geo.amap.AmapResponseNormalizer;
import com.ai.guide.domain.search.model.BaiduSearchResult;
import com.ai.guide.domain.search.service.BaiduSearchService;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("SearchApplicationServiceImpl 应用服务测试")
class SearchApplicationServiceImplTest {

    private final BaiduSearchService baiduSearchService = mock(BaiduSearchService.class);
    private final AmapRouteService amapRouteService = mock(AmapRouteService.class);
    private final SearchApplicationService service = new SearchApplicationServiceImpl(baiduSearchService, amapRouteService, new JsonTaxonomyAdapter());

    @Test
    @DisplayName("searchBaidu - 委派领域服务并包装为标准 Result")
    void testSearchBaidu() {
        BaiduSearchResult item = BaiduSearchResult.builder().title("洪崖洞").build();
        when(baiduSearchService.search("洪崖洞", 10)).thenReturn(List.of(item));

        Result<List<BaiduSearchResult>> result = service.searchBaidu("洪崖洞", 10);
        assertNotNull(result);
        assertEquals(200, result.getCode());
        assertEquals("百度文旅检索成功", result.getMessage());
        assertEquals(1, result.getData().size());
        assertEquals("洪崖洞", result.getData().get(0).getTitle());
    }

    @Test
    @DisplayName("searchAmap - 查询词为空返回 200 及空列表")
    void testSearchAmapEmptyQuery() {
        Result<List<com.ai.guide.domain.geo.model.GeoPoiCandidate>> resultNull = service.searchAmap(null, "重庆市", null);
        assertEquals(200, resultNull.getCode());
        assertEquals("请输入搜索关键词", resultNull.getMessage());
        assertTrue(resultNull.getData().isEmpty());

        Result<List<com.ai.guide.domain.geo.model.GeoPoiCandidate>> resultBlank = service.searchAmap("   ", "重庆市", null);
        assertEquals(200, resultBlank.getCode());
        assertEquals("请输入搜索关键词", resultBlank.getMessage());
        assertTrue(resultBlank.getData().isEmpty());
    }

    @Test
    @DisplayName("searchAmap - 高德检索成功返回 200 与候选列表")
    void testSearchAmapSuccess() {
        com.ai.guide.domain.geo.model.GeoPoiCandidate poi = new com.ai.guide.domain.geo.model.GeoPoiCandidate(
                "1", "磁器口古镇", new com.ai.guide.domain.geo.model.GeoCoordinate(106.44, 29.58),
                "沙坪坝区", "景区", null, null, "1", null, null
        );
        GeoPoiOutcome outcome = new GeoPoiOutcome(
                GeoOutcomeStatus.SUCCESS, List.of(poi), "ok", Instant.now()
        );
        when(amapRouteService.searchPoi(eq("磁器口"), eq("重庆市"), isNull())).thenReturn(outcome);

        Result<List<com.ai.guide.domain.geo.model.GeoPoiCandidate>> result = service.searchAmap(" 磁器口 ", "重庆市", null);
        assertEquals(200, result.getCode());
        assertEquals("高德POI检索成功", result.getMessage());
        assertEquals(1, result.getData().size());
        assertEquals("磁器口古镇", result.getData().get(0).name());
    }

    @Test
    void missingCityUsesConfiguredTaxonomyDefault() {
        when(amapRouteService.searchPoi(eq("博物馆"), eq("重庆市"), isNull()))
                .thenReturn(new GeoPoiOutcome(GeoOutcomeStatus.UNAVAILABLE, List.of(), "offline", Instant.now()));

        service.searchAmap("博物馆", " ", null);

        verify(amapRouteService).searchPoi("博物馆", "重庆市", null);
    }

    @Test
    @DisplayName("searchAmap - 高德返回 INVALID 状态码仍为 200 并提示有效关键词")
    void testSearchAmapInvalidOutcome() {
        GeoPoiOutcome outcome = new GeoPoiOutcome(
                GeoOutcomeStatus.INVALID, List.of(), "关键词非法", Instant.now()
        );
        when(amapRouteService.searchPoi(anyString(), anyString(), any())).thenReturn(outcome);

        Result<List<com.ai.guide.domain.geo.model.GeoPoiCandidate>> result = service.searchAmap("???", "重庆市", null);
        assertEquals(200, result.getCode());
        assertEquals("请输入有效搜索关键词", result.getMessage());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    @DisplayName("searchAmap - 高德服务不可用或超时返回 503")
    void testSearchAmapUnavailable() {
        GeoPoiOutcome outcome = new GeoPoiOutcome(
                GeoOutcomeStatus.UNAVAILABLE, List.of(), "未配置高德服务端密钥", Instant.now()
        );
        when(amapRouteService.searchPoi(anyString(), anyString(), any())).thenReturn(outcome);

        Result<List<com.ai.guide.domain.geo.model.GeoPoiCandidate>> result = service.searchAmap("武隆天生三桥", "重庆市", null);
        assertEquals(503, result.getCode());
        assertEquals("高德检索暂不可用: 未配置高德服务端密钥", result.getMessage());
    }
}

