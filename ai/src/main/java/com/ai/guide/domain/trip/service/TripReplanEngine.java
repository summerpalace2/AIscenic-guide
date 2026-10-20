package com.ai.guide.domain.trip.service;

import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.ai.guide.domain.planner.gateway.RouteGatewayPort;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 正式行程服务端智能重规划引擎
 *
 * 所属领域：domain.trip.service（正式行程服务层）
 * 架构职责：在用户对已保存的正式行程发起重排时，重新调用排程与路线算法生成新快照，杜绝客户端擅自组装非法数据。
 */
@Service
public class TripReplanEngine {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ItineraryBuilderPort itineraryBuilder;
    private final RouteGatewayPort amapPlannerGateway;
    private final ObjectMapper objectMapper;
    private final PlannerTaxonomyPort taxonomy;

    public TripReplanEngine(ItineraryBuilderPort itineraryBuilder,
                            RouteGatewayPort amapPlannerGateway,
                            ObjectMapper objectMapper) {
        this(itineraryBuilder, amapPlannerGateway, objectMapper, TaxonomyPort.empty());
    }

    @Autowired
    public TripReplanEngine(ItineraryBuilderPort itineraryBuilder,
                            RouteGatewayPort amapPlannerGateway,
                            ObjectMapper objectMapper,
                            PlannerTaxonomyPort taxonomy) {
        this.itineraryBuilder = itineraryBuilder;
        this.amapPlannerGateway = amapPlannerGateway;
        this.objectMapper = objectMapper;
        this.taxonomy = taxonomy == null ? TaxonomyPort.empty() : taxonomy;
    }

    public ReplanResult replan(Map<String, Object> source, Map<String, Object> request,
                               int nextVersion) {
        Map<String, Object> trip = deepCopy(source);
        String targetId = text(request == null ? null : request.get("targetStopId"));
        if (targetId.isBlank()) targetId = text(request == null ? null : request.get("entityId"));
        StopLocation target = locateStop(trip, targetId);
        if (target == null) throw new BadRequestException("未在当前行程中找到要替换的站点。");
        if (com.ai.guide.domain.planner.service.PlannerPins.read(source).contains(text(target.stop().get("id"))))
            throw new BadRequestException("该站点已固定，请先在规划会话中取消固定并保存后再替换。");

        String oldVenueId = text(target.stop().get("venueId"));
        if (oldVenueId.isBlank()) oldVenueId = text(target.stop().get("entityId"));
        Attraction old = itineraryBuilder.attraction(oldVenueId);
        if (old == null) throw new BadRequestException("当前站点缺少有效景点实体。");

        Set<String> used = usedVenues(trip);
        used.remove(old.getId());
        String requestedCandidate = text(request == null ? null : request.get("candidateVenueId"));
        String reason = text(request == null ? null : request.get("reason"));
        Attraction replacement;
        if (!requestedCandidate.isBlank()) {
            replacement = itineraryBuilder.attraction(requestedCandidate);
            if (replacement == null) throw new BadRequestException("候选景点不存在。");
            if (replacement.getId().equals(old.getId())) {
                throw new BadRequestException("候选景点不能与当前站点相同。");
            }
            if (used.contains(replacement.getId())) {
                throw new BadRequestException("候选景点已存在于当前行程，不能造成重复。");
            }
        } else {
            replacement = rankReplacement(reason, old, used);
            if (replacement == null) throw new BadRequestException("当前没有可用的替换景点。");
        }

        String stopId = text(target.stop().get("id"));
        if (stopId.isBlank()) stopId = targetId;
        Map<String, Object> newStop = itineraryBuilder.createStop(
                replacement,
                stopId,
                safeValue(target.stop().get("time"), "待安排"),
                safeValue(target.stop().get("duration"), replacement.getDuration()),
                "因为“" + reason + "”，换成更合适的" + replacement.getName() + "（" + com.ai.guide.domain.planner.service.PlannerService.stripTrailingPunctuation(replacement.getSummary()) + "）。",
                "这是服务端局部替换，仅改变目标站点；" + replacement.getIntro());
        copyConstraintAnnotations(target.stop(), newStop);
        target.stops().set(target.index(), newStop);

        trip.put("version", nextVersion);
        trip.put("status", "SAVED");
        if (!reason.isBlank()) trip.put("lastReplanReason", reason);
        trip.put("lastReplannedEntityId", targetId);
        trip.put("subtitle", "局部重规划 · 路线已重新计算");
        appendHistory(trip, nextVersion, "局部重规划", reason, List.of(targetId));
        amapPlannerGateway.hydrate(trip);
        if (!com.ai.guide.domain.planner.service.PlannerPins.retained(source, trip))
            throw new BadRequestException("本次重规划会改变已固定站点，请先取消固定再调整。");
        com.ai.guide.domain.planner.service.PlannerPins.preserve(source, trip);
        return new ReplanResult(trip, "局部重规划", reason, List.of(targetId), replacement.getId());
    }

    private Attraction rankReplacement(String reason, Attraction old, Set<String> used) {
        return itineraryBuilder.attractionServiceList().stream()
                .filter(item -> !used.contains(item.getId()) && !item.getId().equals(old.getId()))
                .map(item -> new Ranked(item, replacementScore(item, old, reason)))
                .max(Comparator.comparingInt(Ranked::score)
                        .thenComparingInt(ranked -> -itineraryBuilder.catalogRank(ranked.attraction().getId())))
                .map(Ranked::attraction)
                .orElse(null);
    }

    private int replacementScore(Attraction candidate, Attraction old, String reason) {
        int score = 0;
        List<String> tags = candidate.getTags() == null ? List.of() : candidate.getTags();
        if (matchesHint(reason, "replanIndoorAliases")) {
            if (Boolean.TRUE.equals(candidate.getIndoor())) score += 20;
            if (tags.contains("室内")) score += 10;
        }
        if (matchesHint(reason, "replanLowWalkingAliases")) {
            if ("低".equals(candidate.getWalkDifficulty())) score += 20;
            if (matchesAny(safe(candidate.getWalk()), "candidateLowWalkingFeatures")) score += 10;
            if (tags.stream().anyMatch(tag -> taxonomy.getHints("candidateLowWalkingFeatures").contains(tag))) score += 10;
        }
        if (matchesHint(reason, "replanUrgencyAliases")) {
            if (matchesAny(safe(candidate.getDuration()), "shortVisitDurationTokens")) score += 20;
            if (tags.contains("短停留")) score += 10;
        }
        if (matchesHint(reason, "replanNightAliases")) {
            if ("夜景".equals(candidate.getCategory()) || tags.contains("夜景")) score += 25;
        }
        if (taxonomy.getHints("foodTags").stream().anyMatch(reason::contains)
                || taxonomy.getHints("dining").stream().anyMatch(reason::contains)) {
            if ("美食".equals(candidate.getCategory()) || tags.contains("美食")) score += 25;
        }
        if (matchesHint(reason, "replanCultureAliases")) {
            if ("文创".equals(candidate.getCategory()) || tags.contains("文创") || tags.contains("拍照")) score += 20;
        }
        if (matchesHint(reason, "replanLowWalkingAliases")
                && ("夜景".equals(old.getCategory()) || old.matchesAnyTagOrFeature("夜景"))
                && ("夜景".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("夜景"))
                && ("低".equals(candidate.getWalkDifficulty()) || "SUPPORTED".equals(candidate.getEffectiveAccessibility()))
                && (matchesAny(safe(candidate.getWalk()), "candidateLowWalkingFeatures") || candidate.matchesAnyTagOrFeature(taxonomy.getHints("candidateLowWalkingFeatures").toArray(String[]::new)))) {
            score += 30;
        }
        if (safe(candidate.getDistrict()).equals(old.getDistrict())) score += 5;
        return score;
    }

    private boolean matchesHint(String text, String key) {
        return matchesAny(text, key);
    }

    private boolean matchesAny(String text, String key) {
        return text != null && taxonomy.getHints(key).stream().anyMatch(text::contains);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopy(Map<String, Object> value) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(value == null ? Map.of() : value), MAP_TYPE);
        } catch (Exception error) {
            throw new IllegalArgumentException("Trip snapshot 无法复制", error);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> days(Map<String, Object> trip) {
        Object value = trip.computeIfAbsent("days", ignored -> new ArrayList<>());
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> stops(Map<String, Object> day) {
        Object value = day.computeIfAbsent("stops", ignored -> new ArrayList<>());
        return value instanceof List<?> list ? (List<Map<String, Object>>) (List<?>) list : new ArrayList<>();
    }

    private StopLocation locateStop(Map<String, Object> trip, String stopId) {
        for (Map<String, Object> day : days(trip)) {
            List<Map<String, Object>> dayStops = stops(day);
            for (int index = 0; index < dayStops.size(); index++) {
                Map<String, Object> stop = dayStops.get(index);
                if (stopId.equals(text(stop.get("id"))) || stopId.equals(text(stop.get("stableStopId")))) {
                    return new StopLocation(day, dayStops, index, stop);
                }
            }
        }
        return null;
    }

    private Set<String> usedVenues(Map<String, Object> trip) {
        Set<String> result = new HashSet<>();
        for (Map<String, Object> day : days(trip)) {
            for (Map<String, Object> stop : stops(day)) {
                String id = text(stop.get("venueId"));
                if (id.isBlank()) id = text(stop.get("entityId"));
                if (!id.isBlank()) result.add(id);
            }
        }
        return result;
    }

    private void copyConstraintAnnotations(Map<String, Object> old, Map<String, Object> next) {
        next.put("matchedConstraints", old.getOrDefault("matchedConstraints", List.of()));
        next.put("routePreference", old.getOrDefault("routePreference", "walking"));
    }

    @SuppressWarnings("unchecked")
    private void appendHistory(Map<String, Object> trip, int version, String label,
                               String reason, List<String> changed) {
        List<Map<String, Object>> history = new ArrayList<>();
        Object old = trip.get("versionHistory");
        if (old instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof Map<?, ?> map) {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    map.forEach((key, item) -> copy.put(String.valueOf(key), item));
                    history.add(copy);
                }
            }
        }
        history.add(Map.of("version", version, "label", label, "changedSegments", changed,
                "reason", safe(reason), "createdAt", Instant.now().toString()));
        trip.put("versionHistory", history);
    }

    private String safeValue(Object value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record ReplanResult(Map<String, Object> plan, String changeReason,
                               String reason, List<String> changedSegments,
                               String replacementVenueId) {
    }

    private record StopLocation(Map<String, Object> day, List<Map<String, Object>> stops,
                                int index, Map<String, Object> stop) {
    }

    private record Ranked(Attraction attraction, int score) {
    }

    public static class BadRequestException extends RuntimeException {
        public BadRequestException(String message) {
            super(message);
        }
    }
}
