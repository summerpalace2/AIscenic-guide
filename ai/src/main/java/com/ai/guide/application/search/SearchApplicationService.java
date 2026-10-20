package com.ai.guide.application.search;

import com.ai.guide.common.model.Result;
import com.ai.guide.domain.search.model.BaiduSearchResult;

import java.util.List;

/**
 * 文旅综合搜索应用服务门面 (Application Service)
 *
 * 所属应用层：application.search
 * 职责：编排全网百度搜索与高德地理 POI 检索用例，统一向适配层提供用例契约与异常容灾保障。
 */
public interface SearchApplicationService {

    /**
     * 百度全网及本地文旅融合搜索
     *
     * @param q     搜索关键词
     * @param limit 返回上限
     * @return 搜索结果列表
     */
    Result<List<BaiduSearchResult>> searchBaidu(String q, int limit);

    /**
     * 高德实景 POI 地理搜索引擎
     *
     * @param q     搜索关键词
     * @param city  城市范围
     * @param types POI 类型过滤
     * @return 候选 POI 列表
     */
    Result<List<com.ai.guide.domain.geo.model.GeoPoiCandidate>> searchAmap(String q, String city, String types);
}
