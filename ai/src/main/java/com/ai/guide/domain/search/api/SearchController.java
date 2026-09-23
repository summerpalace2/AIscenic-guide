package com.ai.guide.domain.search.api;

import com.ai.guide.common.model.Result;
import com.ai.guide.domain.planner.service.AmapRouteService;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.search.model.BaiduSearchResult;
import com.ai.guide.domain.search.service.BaiduSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文旅搜索控制器（集成百度全网搜索与高德POI搜索引擎）
 */
@RestController
@RequestMapping("/ai/search")
public class SearchController {

    private final BaiduSearchService baiduSearchService;
    private final AmapRouteService amapRouteService;

    public SearchController(BaiduSearchService baiduSearchService, AmapRouteService amapRouteService) {
        this.baiduSearchService = baiduSearchService;
        this.amapRouteService = amapRouteService;
    }

    @GetMapping("/baidu")
    public Result<List<BaiduSearchResult>> searchBaidu(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {
        List<BaiduSearchResult> results = baiduSearchService.search(q, limit);
        return Result.success("百度文旅检索成功", results);
    }

    @GetMapping("/amap")
    public Result<List<AmapResponseNormalizer.PoiCandidate>> searchAmap(
            @RequestParam String q,
            @RequestParam(defaultValue = "重庆市") String city,
            @RequestParam(required = false) String types) {
        if (q == null || q.isBlank()) {
            return Result.success("请输入搜索关键词", List.of());
        }
        AmapRouteService.PoiOutcome outcome = amapRouteService.searchPoi(q.trim(), city, types);
        if (outcome.status() == AmapRouteService.OutcomeStatus.SUCCESS) {
            return Result.success("高德POI检索成功", outcome.candidates());
        } else if (outcome.status() == AmapRouteService.OutcomeStatus.INVALID) {
            return Result.success("请输入有效搜索关键词", List.of());
        } else {
            return Result.error(503, "高德检索暂不可用: " + outcome.reason());
        }
    }
}
