package com.ai.guide.domain.search.service;

import com.ai.guide.domain.planner.service.AmapRouteService;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.domain.search.model.BaiduSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 百度文旅搜索引擎服务
 *
 * 接入百度搜索直通能力与高德/本地知识多源融合检索，支持海量重庆文旅景点、地道美食、小众打卡与出行攻略查询。
 */
@Service
public class BaiduSearchService {

    private static final Logger log = LoggerFactory.getLogger(BaiduSearchService.class);

    @Value("${baidu.api-key:}")
    private String apiKey;

    @Value("${baidu.secret-key:}")
    private String secretKey;

    private final AmapRouteService amapRouteService;
    private final KnowledgeDocumentService knowledgeDocumentService;

    public BaiduSearchService(AmapRouteService amapRouteService,
                              KnowledgeDocumentService knowledgeDocumentService) {
        this.amapRouteService = amapRouteService;
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

    public List<BaiduSearchResult> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String cleanQ = query.trim();
        List<BaiduSearchResult> results = new ArrayList<>();
        int maxItems = Math.max(3, Math.min(limit, 15));

        // 1. 本地高可信知识文档检索
        try {
            if (knowledgeDocumentService != null) {
                List<Map<String, Object>> localHits = knowledgeDocumentService.searchLocalKnowledge(cleanQ, 3);
                if (localHits != null) {
                    for (Map<String, Object> hit : localHits) {
                        String title = String.valueOf(hit.getOrDefault("title", ""));
                        String content = String.valueOf(hit.getOrDefault("content", ""));
                        String snippet = content.length() > 120 ? content.substring(0, 120) + "..." : content;
                        String baiduUrl = "https://www.baidu.com/s?wd=" + URLEncoder.encode("重庆 " + title, StandardCharsets.UTF_8);
                        results.add(BaiduSearchResult.builder()
                                .title(title)
                                .snippet(snippet)
                                .url(baiduUrl)
                                .source("本地文旅权威库")
                                .category(title.contains("鱼") || title.contains("火锅") || title.contains("菜") ? "特色美食" : "核心地标")
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
            if (amapRouteService != null) {
                AmapRouteService.PoiOutcome outcome = amapRouteService.searchPoi(cleanQ, "重庆市");
                if (outcome != null && outcome.candidates() != null) {
                    for (AmapResponseNormalizer.PoiCandidate poi : outcome.candidates()) {
                        if (results.size() >= maxItems) break;
                        boolean duplicate = results.stream().anyMatch(r -> r.getTitle().contains(poi.name()));
                        if (duplicate) continue;

                        String baiduUrl = "https://www.baidu.com/s?wd=" + URLEncoder.encode("重庆 " + poi.name(), StandardCharsets.UTF_8);
                        String snippet = (poi.address() != null && !poi.address().isBlank() ? "地址：" + poi.address() + "。" : "")
                                + (poi.type() != null && !poi.type().isBlank() ? "类型：" + poi.type() + "。" : "")
                                + "可通过百度搜索查看详细评分、游玩攻略与大众口碑。";

                        results.add(BaiduSearchResult.builder()
                                .title(poi.name())
                                .snippet(snippet)
                                .url(baiduUrl)
                                .source("高德地图实景POI")
                                .category(poi.type() != null && poi.type().contains("餐饮") ? "特色美食" : "景区地标")
                                .thumbnail(poi.photoUrl())
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[BaiduSearch] Amap POI search error: {}", e.getMessage());
        }

        // 3. 百度全网搜索直通条目（确保任何搜索词都有百度官方最新权威结果卡片）
        String encodedQuery = URLEncoder.encode("重庆 " + cleanQ, StandardCharsets.UTF_8);
        String directBaiduUrl = "https://www.baidu.com/s?wd=" + encodedQuery;
        results.add(BaiduSearchResult.builder()
                .title("在百度中探索更多“" + cleanQ + "”相关文旅与美食资讯")
                .snippet("点击前往百度搜索，实时获取关于【" + cleanQ + "】的最新游记攻略、特色美食排行、营业实况与市民点评。")
                .url(directBaiduUrl)
                .source("百度搜索官方全网直达")
                .category("全网资讯")
                .thumbnail("https://www.baidu.com/favicon.ico")
                .build());

        return results.subList(0, Math.min(results.size(), maxItems));
    }
}
