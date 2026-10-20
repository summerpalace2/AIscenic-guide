package com.ai.guide.adapter.search.web;

import com.ai.guide.application.search.SearchApplicationService;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.search.model.BaiduSearchResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文旅搜索控制器（集成百度全网搜索与高德POI搜索引擎）
 *
 * 所属适配层：adapter.search.web
 * 职责：接收外部 HTTP 检索请求，校验基本参数，委派搜索应用服务完成多源搜索编排。
 */
@RestController
@RequestMapping("/ai/search")
public class SearchController {

    private final SearchApplicationService searchApplicationService;

    public SearchController(SearchApplicationService searchApplicationService) {
        this.searchApplicationService = searchApplicationService;
    }

    @GetMapping("/baidu")
    public Result<List<BaiduSearchResult>> searchBaidu(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int limit) {
        return searchApplicationService.searchBaidu(q, limit);
    }

    @GetMapping("/amap")
    public Result<List<com.ai.guide.domain.geo.model.GeoPoiCandidate>> searchAmap(
            @RequestParam String q,
            @RequestParam(defaultValue = "") String city,
            @RequestParam(required = false) String types) {
        return searchApplicationService.searchAmap(q, city, types);
    }
}
