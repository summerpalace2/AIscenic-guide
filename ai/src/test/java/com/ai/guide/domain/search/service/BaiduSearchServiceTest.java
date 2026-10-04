package com.ai.guide.domain.search.service;

import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.domain.search.gateway.PoiSearchGatewayPort;
import com.ai.guide.domain.search.model.BaiduSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BaiduSearchService 领域服务测试")
class BaiduSearchServiceTest {

    private final PoiSearchGatewayPort poiSearchGatewayPort = mock(PoiSearchGatewayPort.class);
    private final KnowledgeDocumentService knowledgeDocumentService = mock(KnowledgeDocumentService.class);
    private final BaiduSearchService service = new BaiduSearchService(poiSearchGatewayPort, knowledgeDocumentService);

    @Test
    @DisplayName("search - 空查询直接返回空列表")
    void testSearchEmptyQuery() {
        assertTrue(service.search(null, 10).isEmpty());
        assertTrue(service.search("", 10).isEmpty());
        assertTrue(service.search("   ", 10).isEmpty());
    }

    @Test
    @DisplayName("search - 融合本地知识、高德POI与百度直达卡片")
    void testSearchFusion() {
        // 1. 本地知识命中
        Map<String, Object> localHit = Map.of(
                "title", "重庆老火锅",
                "content", "重庆传统火锅以九宫格和牛油为特色，历史悠久。"
        );
        when(knowledgeDocumentService.searchLocalKnowledge(eq("火锅"), eq(3)))
                .thenReturn(List.of(localHit));

        // 2. 高德 POI 命中
        AmapResponseNormalizer.PoiCandidate poi = new AmapResponseNormalizer.PoiCandidate(
                "poi-1", "佩姐老火锅(洪崖洞店)", new AmapResponseNormalizer.Coordinate(106.5, 29.5),
                "渝中区", "餐饮服务;火锅店", null, null, "poi-1", "http://img.jpg", null
        );
        when(poiSearchGatewayPort.searchPoiCandidates(eq("火锅"), eq("重庆市")))
                .thenReturn(List.of(poi));

        List<BaiduSearchResult> results = service.search("火锅", 5);
        assertNotNull(results);
        assertFalse(results.isEmpty());

        // 包含本地知识卡片
        assertTrue(results.stream().anyMatch(r -> "本地文旅权威库".equals(r.getSource()) && r.getTitle().contains("重庆老火锅")));
        // 包含高德POI卡片
        assertTrue(results.stream().anyMatch(r -> "高德地图实景POI".equals(r.getSource()) && r.getTitle().contains("佩姐老火锅")));
        // 包含百度全网直达卡片
        assertTrue(results.stream().anyMatch(r -> "百度搜索官方全网直达".equals(r.getSource())));
    }

    @Test
    @DisplayName("search - 当依赖服务异常时平滑降级，仅返回百度全网卡片")
    void testSearchGracefulDegradation() {
        when(knowledgeDocumentService.searchLocalKnowledge(anyString(), anyInt()))
                .thenThrow(new RuntimeException("DB down"));
        when(poiSearchGatewayPort.searchPoiCandidates(anyString(), anyString()))
                .thenThrow(new RuntimeException("Amap network timeout"));

        List<BaiduSearchResult> results = service.search("小面", 5);
        assertNotNull(results);
        assertEquals(1, results.size());
        assertEquals("百度搜索官方全网直达", results.get(0).getSource());
        assertTrue(results.get(0).getTitle().contains("小面"));
    }
}
