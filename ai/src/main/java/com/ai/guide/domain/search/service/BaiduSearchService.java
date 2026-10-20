package com.ai.guide.domain.search.service;

import com.ai.guide.domain.search.gateway.LocalKnowledgeSearchPort;
import com.ai.guide.domain.search.gateway.PoiSearchGatewayPort;
import com.ai.guide.domain.search.gateway.WebSearchLinkPort;
import com.ai.guide.domain.search.model.BaiduSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ai.guide.domain.taxonomy.gateway.SearchTaxonomyPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 百度文旅搜索引擎服务
 *
 * 接入百度搜索直通能力与高德/本地知识多源融合检索，支持多城市文旅地点与出行攻略查询。
 */
@Service
public class BaiduSearchService {

    private static final Logger log = LoggerFactory.getLogger(BaiduSearchService.class);

    private final PoiSearchGatewayPort poiSearchGatewayPort;
    private final LocalKnowledgeSearchPort localKnowledgeSearch;
    private final SearchTaxonomyPort taxonomy;
    private final WebSearchLinkPort webSearchLink;

    @Autowired
    public BaiduSearchService(PoiSearchGatewayPort poiSearchGatewayPort,
                              LocalKnowledgeSearchPort localKnowledgeSearch,
                              SearchTaxonomyPort taxonomy,
                              WebSearchLinkPort webSearchLink) {
        this.poiSearchGatewayPort = poiSearchGatewayPort;
        this.localKnowledgeSearch = localKnowledgeSearch;
        this.taxonomy = taxonomy;
        this.webSearchLink = webSearchLink;
    }

    public List<BaiduSearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String cleanQ = query.trim();
        String city = resolveCity(cleanQ);
        List<BaiduSearchResult> results = new ArrayList<>();
        int maxItems = Math.max(3, Math.min(limit, 15));

        // 1. 本地高可信知识文档检索
        try {
            if (localKnowledgeSearch != null) {
                List<com.ai.guide.domain.search.model.LocalKnowledgeHit> localHits = localKnowledgeSearch.search(cleanQ, 3);
                if (localHits != null) {
                    for (var hit : localHits) {
                        String title = String.valueOf(hit.title());
                        String content = String.valueOf(hit.content());
                        String snippet = content.length() > 120 ? content.substring(0, 120) + "..." : content;
                        String baiduUrl = webSearchLink.searchUrl(city + " " + title);
                        results.add(BaiduSearchResult.builder()
                                .title(title)
                                .snippet(snippet)
                                .url(baiduUrl)
                                .source("本地文旅权威库")
                                .category(taxonomy.getHints("dining").stream().anyMatch(title::contains)
                                        ? taxonomy.getHints("defaultDiningDisplayLabel").stream().findFirst().orElse("")
                                        : taxonomy.getHints("searchCategoryCoreLandmark").stream().findFirst().orElse(""))
                                .thumbnail(null)
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[BaiduSearch] Local knowledge search error: {}", e.getMessage());
        }

        // 2. 高德实时 POI 与实景影像检索（为搜索结果提供权威地理与真实照片）
        try {
            if (poiSearchGatewayPort != null) {
                String providerCity = city.endsWith("市") ? city : city + "市";
                List<com.ai.guide.domain.geo.model.GeoPoiCandidate> candidates = poiSearchGatewayPort.searchPoiCandidates(cleanQ, providerCity);
                if (candidates != null) {
                    for (com.ai.guide.domain.geo.model.GeoPoiCandidate poi : candidates) {
                        if (results.size() >= maxItems) break;
                        boolean duplicate = results.stream().anyMatch(r -> r.getTitle().contains(poi.name()));
                        if (duplicate) continue;

                        String baiduUrl = webSearchLink.searchUrl(city + " " + poi.name());
                        String snippet = (poi.address() != null && !poi.address().isBlank() ? "地址：" + poi.address() + "。" : "")
                                + (poi.type() != null && !poi.type().isBlank() ? "类型：" + poi.type() + "。" : "")
                                + "可通过百度搜索查看详细评分、游玩攻略与大众口碑。";

                        results.add(BaiduSearchResult.builder()
                                .title(poi.name())
                                .snippet(snippet)
                                .url(baiduUrl)
                                .source("高德地图实景POI")
                                .category(poi.type() != null && poi.type().contains("餐饮")
                                        ? taxonomy.getHints("defaultDiningDisplayLabel").stream().findFirst().orElse("")
                                        : taxonomy.getHints("searchCategoryScenicLandmark").stream().findFirst().orElse(""))
                                .thumbnail(poi.photoUrl())
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[BaiduSearch] Amap POI search error: {}", e.getMessage());
        }

        // 3. 百度全网搜索直通条目（确保任何搜索词都有百度官方最新权威结果卡片）
        String directBaiduUrl = webSearchLink.searchUrl(city + " " + cleanQ);
        results.add(BaiduSearchResult.builder()
                .title("在百度中探索更多“" + cleanQ + "”相关文旅与美食资讯")
                .snippet("点击前往百度搜索，实时获取关于【" + cleanQ + "】的最新游记攻略、特色美食排行、营业实况与市民点评。")
                .url(directBaiduUrl)
                .source("百度搜索官方全网直达")
                .category(taxonomy.getHints("searchCategoryWebNews").stream().findFirst().orElse(""))
                .thumbnail(webSearchLink.faviconUrl())
                .build());

        return results.subList(0, Math.min(results.size(), maxItems));
    }

    private String resolveCity(String query) {
        return taxonomy.getCityAliases().entrySet().stream()
                .filter(entry -> query.contains(entry.getKey()))
                .sorted(java.util.Comparator.<java.util.Map.Entry<String, String>>comparingInt(entry -> entry.getKey().length()).reversed())
                .map(java.util.Map.Entry::getValue)
                .findFirst().orElseGet(taxonomy::getDefaultCity);
    }
}
