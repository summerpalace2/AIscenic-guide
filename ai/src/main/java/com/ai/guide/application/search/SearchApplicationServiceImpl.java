package com.ai.guide.application.search;

import com.ai.guide.common.model.Result;
import com.ai.guide.domain.search.model.BaiduSearchResult;
import com.ai.guide.domain.search.service.BaiduSearchService;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.taxonomy.gateway.SearchTaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final GeoRouteGatewayPort amapRouteService;
    private final SearchTaxonomyPort taxonomy;

    @Autowired
    public SearchApplicationServiceImpl(BaiduSearchService baiduSearchService,
                                        GeoRouteGatewayPort amapRouteService,
                                        SearchTaxonomyPort taxonomy) {
        this.baiduSearchService = baiduSearchService;
        this.amapRouteService = amapRouteService;
        this.taxonomy = taxonomy;
    }

    public SearchApplicationServiceImpl(BaiduSearchService baiduSearchService,
                                        GeoRouteGatewayPort amapRouteService) {
        this(baiduSearchService, amapRouteService, com.ai.guide.domain.taxonomy.gateway.TaxonomyPort.empty());
    }

    @Override
    public Result<List<BaiduSearchResult>> searchBaidu(String q, int limit) {
        List<BaiduSearchResult> results = baiduSearchService.search(q, limit);
        return Result.success("百度文旅检索成功", results);
    }

    @Override
    public Result<List<com.ai.guide.domain.geo.model.GeoPoiCandidate>> searchAmap(String q, String city, String types) {
        if (q == null || q.isBlank()) {
            return Result.success("请输入搜索关键词", List.of());
        }
        String effectiveCity = city == null || city.isBlank() ? taxonomy.getDefaultCity() : city.trim();
        if (effectiveCity != null && !effectiveCity.isBlank() && !effectiveCity.endsWith("市")) effectiveCity += "市";
        GeoPoiOutcome outcome = amapRouteService.searchPoi(q.trim(), effectiveCity, types);
        if (outcome == null) return Result.error(503, "高德检索暂不可用: provider returned no outcome");
        if (outcome.status() == GeoOutcomeStatus.SUCCESS) {
            return Result.success("高德POI检索成功", outcome.candidates());
        } else if (outcome.status() == GeoOutcomeStatus.INVALID) {
            return Result.success("请输入有效搜索关键词", List.of());
        } else {
            return Result.error(503, "高德检索暂不可用: " + outcome.reason());
        }
    }
}
