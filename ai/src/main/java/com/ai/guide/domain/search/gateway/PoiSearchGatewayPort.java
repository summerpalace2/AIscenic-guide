package com.ai.guide.domain.search.gateway;

import java.util.List;

/**
 * POI 检索领域网关端口 (Gateway Port)
 *
 * 所属领域：domain.search.gateway
 * 职责：定义搜索域获取地理 POI 实景候选结果的契约，隔离底层高德地图技术实现，实现依赖倒置 (DIP)。
 */
public interface PoiSearchGatewayPort {

    /**
     * 根据关键词和城市检索 POI 候选列表
     *
     * @param keywords 搜索关键词
     * @param city     检索城市范围
     * @return POI 候选结果列表（若无结果或异常返回空列表，不返回 null）
     */
    List<com.ai.guide.domain.geo.model.GeoPoiCandidate> searchPoiCandidates(String keywords, String city);
}
