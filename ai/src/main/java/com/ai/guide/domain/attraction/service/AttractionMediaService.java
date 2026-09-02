package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.service.AmapRouteService;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 景点详情实时多媒体数据富集服务
 *
 * 所属领域：domain.attraction.service（文旅地标与百科服务层）
 * 架构职责：在用户查询单景点详情时，按需向高德 Web 服务请求实时图文/POI 多媒体数据进行请求级富集，不持久化外部脏数据到主数据库。
 */
@Service
public class AttractionMediaService {

    private final AmapRouteService routeService;

    public AttractionMediaService(AmapRouteService routeService) {
        this.routeService = routeService;
    }

    public Attraction enrich(Attraction attraction) {
        if (attraction == null) return null;
        attraction.setImage(null);
        attraction.setImageSource("高德 Web Service API");
        attraction.setImagePoiId(null);
        attraction.setImageFetchedAt(null);

        if (!routeService.isConfigured()) {
            attraction.setImageStatus("未查询");
            attraction.setImageReason("未配置高德服务端密钥，未返回景区图片。");
            return attraction;
        }

        List<String> keywords = keywords(attraction);
        AmapResponseNormalizer.PoiCandidate selected = null;
        String reason = "未找到可匹配的高德 POI。";
        for (String keyword : keywords) {
            AmapRouteService.PoiOutcome outcome = routeService.searchPoi(keyword, "重庆市");
            reason = outcome.reason() == null || outcome.reason().isBlank() ? reason : outcome.reason();
            selected = select(outcome.candidates(), attraction);
            if (selected != null) break;
        }

        if (selected == null) {
            attraction.setImageStatus("未返回");
            attraction.setImageReason(reason);
            return attraction;
        }

        AmapResponseNormalizer.PoiCandidate detailed = selected;
        AmapRouteService.PoiOutcome detail = routeService.poiDetail(selected.poiId());
        if (detail.status() == AmapRouteService.OutcomeStatus.SUCCESS) {
            AmapResponseNormalizer.PoiCandidate provider = select(detail.candidates(), attraction);
            if (provider != null) detailed = provider;
        } else if (detail.reason() != null && !detail.reason().isBlank()) {
            reason = detail.reason();
        }

        attraction.setImagePoiId(detailed.poiId());
        attraction.setImageFetchedAt((detail.status() == AmapRouteService.OutcomeStatus.SUCCESS
                ? detail.fetchedAt() : java.time.Instant.now()).toString());
        if (detailed.photoUrl() != null && !detailed.photoUrl().isBlank()) {
            attraction.setImage(detailed.photoUrl());
            attraction.setImageStatus("已返回");
            attraction.setImageReason(detailed.photoTitle() == null || detailed.photoTitle().isBlank()
                    ? "图片由本次高德 POI 查询返回。"
                    : "图片由本次高德 POI 查询返回：" + detailed.photoTitle());
        } else {
            attraction.setImageStatus("无图片");
            attraction.setImageReason("高德已返回匹配 POI，但本次没有可用图片。");
        }
        return attraction;
    }

    private List<String> keywords(Attraction attraction) {
        List<String> result = new ArrayList<>();
        Map<String, Object> query = attraction.getAmapQuery();
        if (query != null) {
            addStrings(result, query.get("keywords"));
            addStrings(result, query.get("matches"));
            addStrings(result, query.get("alternatives"));
        }
        if (result.isEmpty() && attraction.getName() != null) result.add(attraction.getName());
        return result.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
    }

    private void addStrings(List<String> target, Object value) {
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) if (item != null) target.add(String.valueOf(item));
        } else if (value != null) {
            target.add(String.valueOf(value));
        }
    }

    private AmapResponseNormalizer.PoiCandidate select(
            List<AmapResponseNormalizer.PoiCandidate> candidates, Attraction attraction) {
        if (candidates == null) return null;
        List<String> expectedNames = keywords(attraction).stream()
                .map(String::toLowerCase)
                .toList();
        return candidates.stream()
                .filter(candidate -> candidate != null && candidate.coordinate() != null
                        && candidate.poiId() != null && !candidate.poiId().isBlank()
                        && candidate.name() != null && !candidate.name().isBlank())
                .map(candidate -> new Scored(candidate, score(candidate.name(), expectedNames)))
                .filter(item -> item.score() >= 20)
                .max((left, right) -> Integer.compare(left.score(), right.score()))
                .map(Scored::candidate)
                .orElse(null);
    }

    private int score(String name, List<String> expectedNames) {
        String actual = name.toLowerCase();
        int best = 10;
        for (String expected : expectedNames) {
            if (expected.isBlank()) continue;
            if (actual.equals(expected)) best = Math.max(best, 100);
            else if (actual.contains(expected) || expected.contains(actual)) best = Math.max(best, 70);
        }
        return best;
    }

    private record Scored(AmapResponseNormalizer.PoiCandidate candidate, int score) {
    }
}
