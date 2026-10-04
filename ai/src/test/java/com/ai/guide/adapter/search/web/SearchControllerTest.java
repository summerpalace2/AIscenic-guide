package com.ai.guide.adapter.search.web;

import com.ai.guide.application.search.SearchApplicationService;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.search.model.BaiduSearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("SearchController 适配层端点测试")
class SearchControllerTest {

    private final SearchApplicationService applicationService = mock(SearchApplicationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new SearchController(applicationService)).build();

    @Test
    @DisplayName("GET /ai/search/baidu - 成功检索并带默认 limit=10")
    void testSearchBaidu() throws Exception {
        BaiduSearchResult item = BaiduSearchResult.builder()
                .title("洪崖洞民俗风貌区")
                .snippet("国家4A级旅游景区")
                .url("https://www.baidu.com/s?wd=洪崖洞")
                .source("本地文旅权威库")
                .category("核心地标")
                .build();
        when(applicationService.searchBaidu(eq("洪崖洞"), eq(10)))
                .thenReturn(Result.success("百度文旅检索成功", List.of(item)));

        mockMvc.perform(get("/ai/search/baidu").param("q", "洪崖洞"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("百度文旅检索成功"))
                .andExpect(jsonPath("$.data[0].title").value("洪崖洞民俗风貌区"));
    }

    @Test
    @DisplayName("GET /ai/search/amap - 成功检索高德 POI")
    void testSearchAmapSuccess() throws Exception {
        AmapResponseNormalizer.PoiCandidate poi = new AmapResponseNormalizer.PoiCandidate(
                "B001", "解放碑步行街", new AmapResponseNormalizer.Coordinate(106.57, 29.55),
                "渝中区", "风景名胜", null, null, "B001", "http://photo.jpg", null
        );
        when(applicationService.searchAmap(eq("解放碑"), eq("重庆市"), eq("风景名胜")))
                .thenReturn(Result.success("高德POI检索成功", List.of(poi)));

        mockMvc.perform(get("/ai/search/amap")
                        .param("q", "解放碑")
                        .param("city", "重庆市")
                        .param("types", "风景名胜"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("高德POI检索成功"))
                .andExpect(jsonPath("$.data[0].poiId").value("B001"))
                .andExpect(jsonPath("$.data[0].name").value("解放碑步行街"));
    }

    @Test
    @DisplayName("GET /ai/search/amap - 高德服务不可用时返回 503")
    void testSearchAmapServiceUnavailable() throws Exception {
        when(applicationService.searchAmap(eq("大足石刻"), eq("重庆市"), anyString()))
                .thenReturn(Result.error(503, "高德检索暂不可用: 未配置高德服务端密钥"));

        mockMvc.perform(get("/ai/search/amap")
                        .param("q", "大足石刻")
                        .param("types", "110000"))
                .andExpect(status().isOk()) // Controller 返回 ResponseEntity 包装的 Result 或直接 Result
                .andExpect(jsonPath("$.code").value(503))
                .andExpect(jsonPath("$.message").value("高德检索暂不可用: 未配置高德服务端密钥"));
    }
}
