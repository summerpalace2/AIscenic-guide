package com.ai.guide.application.search;

import com.ai.guide.common.model.Result;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.search.model.BaiduSearchResult;
import com.ai.guide.domain.search.service.BaiduSearchService;
import com.ai.guide.infrastructure.planner.amap.AmapRouteService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文旅综合搜索应用服务实现
 *
 * 所属应用层：application.search
 * 职责：编排本地权威知识、高德 POI 检索与百度搜索聚合用例。
 */
@Service
public class SearchApplicationServiceImpl implements SearchApplicationService {

    private final BaiduSearchService baiduSearchService;
    private final AmapRouteService amapRouteService;

    public SearchApplicationServiceImpl(BaiduSearchService baiduSearchService,
                                       AmapRouteService amapRouteService) {
        this.baiduSearchService = baiduSearchService;
        this.amapRouteService = amapRouteService;
    }

    @Override
    public Result<List<BaiduSearchResult>> searchBaidu(String q, int limit) {
        List<BaiduSearchResult> results = baiduSearchService.search(q, limit);
        return Result.success("百度文旅检索成功", results);
    }

    @Override
    public Result<List<AmapResponseNormalizer.PoiCandidate>> searchAmap(String q, String city, String types) {
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
