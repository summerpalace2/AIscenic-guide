package com.ai.guide.application.search;

import com.ai.guide.common.model.Result;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.search.model.BaiduSearchResult;
import com.ai.guide.domain.search.service.BaiduSearchService;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;
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
    private final SearchApplicationService service = new SearchApplicationServiceImpl(baiduSearchService, amapRouteService);

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
        Result<List<AmapResponseNormalizer.PoiCandidate>> resultNull = service.searchAmap(null, "重庆市", null);
        assertEquals(200, resultNull.getCode());
        assertEquals("请输入搜索关键词", resultNull.getMessage());
        assertTrue(resultNull.getData().isEmpty());

        Result<List<AmapResponseNormalizer.PoiCandidate>> resultBlank = service.searchAmap("   ", "重庆市", null);
        assertEquals(200, resultBlank.getCode());
        assertEquals("请输入搜索关键词", resultBlank.getMessage());
        assertTrue(resultBlank.getData().isEmpty());
    }

    @Test
    @DisplayName("searchAmap - 高德检索成功返回 200 与候选列表")
    void testSearchAmapSuccess() {
        AmapResponseNormalizer.PoiCandidate poi = new AmapResponseNormalizer.PoiCandidate(
                "1", "磁器口古镇", new AmapResponseNormalizer.Coordinate(106.44, 29.58),
                "沙坪坝区", "景区", null, null, "1", null, null
        );
        AmapRouteService.PoiOutcome outcome = new AmapRouteService.PoiOutcome(
                AmapRouteService.OutcomeStatus.SUCCESS, List.of(poi), "ok", Instant.now()
        );
        when(amapRouteService.searchPoi(eq("磁器口"), eq("重庆市"), isNull())).thenReturn(outcome);

        Result<List<AmapResponseNormalizer.PoiCandidate>> result = service.searchAmap(" 磁器口 ", "重庆市", null);
        assertEquals(200, result.getCode());
        assertEquals("高德POI检索成功", result.getMessage());
        assertEquals(1, result.getData().size());
        assertEquals("磁器口古镇", result.getData().get(0).name());
    }

    @Test
    @DisplayName("searchAmap - 高德返回 INVALID 状态码仍为 200 并提示有效关键词")
    void testSearchAmapInvalidOutcome() {
        AmapRouteService.PoiOutcome outcome = new AmapRouteService.PoiOutcome(
                AmapRouteService.OutcomeStatus.INVALID, List.of(), "关键词非法", Instant.now()
        );
        when(amapRouteService.searchPoi(anyString(), anyString(), any())).thenReturn(outcome);

        Result<List<AmapResponseNormalizer.PoiCandidate>> result = service.searchAmap("???", "重庆市", null);
        assertEquals(200, result.getCode());
        assertEquals("请输入有效搜索关键词", result.getMessage());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    @DisplayName("searchAmap - 高德服务不可用或超时返回 503")
    void testSearchAmapUnavailable() {
        AmapRouteService.PoiOutcome outcome = new AmapRouteService.PoiOutcome(
                AmapRouteService.OutcomeStatus.UNAVAILABLE, List.of(), "未配置高德服务端密钥", Instant.now()
        );
        when(amapRouteService.searchPoi(anyString(), anyString(), any())).thenReturn(outcome);

        Result<List<AmapResponseNormalizer.PoiCandidate>> result = service.searchAmap("武隆天生三桥", "重庆市", null);
        assertEquals(503, result.getCode());
        assertEquals("高德检索暂不可用: 未配置高德服务端密钥", result.getMessage());
    }
}
