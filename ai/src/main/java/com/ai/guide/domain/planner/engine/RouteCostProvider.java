package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.model.OutcomeStatus;
import com.ai.guide.domain.planner.model.RoutePairOutcome;
import com.ai.guide.domain.planner.service.PlannerMetricsLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 路线通行成本提供者接口
 *
 * 所属领域：domain.planner.engine（规划引擎核心算法层）
 * 架构职责：定义规划算法查询任意两景点间通行耗时与路线指标的标准契约。
 */
@Service
public class RouteCostProvider {

    private final GeoRouteGatewayPort geoRouteGateway;
    private final PlannerMetricsLogger metricsLogger;
    private final Map<String, RouteCost> finalEdgeCache = new ConcurrentHashMap<>();
    private final AtomicInteger finalEdgeProviderCalls = new AtomicInteger();

    @Autowired
    public RouteCostProvider(@Autowired(required = false) GeoRouteGatewayPort geoRouteGateway,
                             @Autowired(required = false) PlannerMetricsLogger metricsLogger) {
        this.geoRouteGateway = geoRouteGateway;
        this.metricsLogger = metricsLogger != null ? metricsLogger : new PlannerMetricsLogger();
    }

    public RouteCostProvider(GeoRouteGatewayPort geoRouteGateway) {
        this(geoRouteGateway, new PlannerMetricsLogger());
    }

    /**
     * Draft-stage cost. Never calls AMap, so candidate selection cannot trigger a matrix-sized request burst.
     */
    public RouteCost calculate(Attraction origin, Attraction destination, String preference) {
        if (origin == null || destination == null) {
            RouteCost unavail = RouteCost.unavailable("起终点景点为空");
            if (metricsLogger != null) metricsLogger.recordRouteCost(unavail.status());
            return unavail;
        }
        if (origin.getId().equals(destination.getId())) {
            RouteCost same = RouteCost.estimated(0, 0, 0, "WALKING", "同景点无需交通");
            if (metricsLogger != null) metricsLogger.recordRouteCost(same.status());
            return same;
        }
        RouteCost estimated = estimateFromCoordinates(origin, destination, preference);
        if (metricsLogger != null) metricsLogger.recordRouteCost(estimated.status());
        return estimated;
    }

    /**
     * Final-edge stage cost. Cache-first and allowed to call AMap for a completed draft's adjacent edge only.
     */
    public RouteCost resolveFinalEdge(Attraction origin, Attraction destination, String preference) {
        if (origin == null || destination == null) {
            RouteCost unavail = RouteCost.unavailable("起终点景点为空");
            if (metricsLogger != null) metricsLogger.recordRouteCost(unavail.status());
            return unavail;
        }
        if (origin.getId().equals(destination.getId())) {
            RouteCost same = RouteCost.estimated(0, 0, 0, "WALKING", "同景点无需交通");
            if (metricsLogger != null) metricsLogger.recordRouteCost(same.status());
            return same;
        }

        String cacheKey = edgeKey(origin, destination, preference);
        RouteCost cached = finalEdgeCache.get(cacheKey);
        if (cached != null) {
            RouteCost res = cached.asCached();
            if (metricsLogger != null) metricsLogger.recordRouteCost(res.status());
            return res;
        }

        RouteCost verified = requestExactRoute(origin, destination, preference);
        if (verified != null && verified.status() == RouteCost.RouteDataStatus.VERIFIED_AMAP) {
            finalEdgeCache.put(cacheKey, verified);
            if (metricsLogger != null) metricsLogger.recordRouteCost(verified.status());
            return verified;
        }

        // Unavailable external service must never masquerade as a verified route. Keep a usable estimate.
        RouteCost fallback = estimateFromCoordinates(origin, destination, preference);
        if (metricsLogger != null) metricsLogger.recordRouteCost(fallback.status());
        return fallback;
    }

    /** Stable key includes endpoints and route preference. */
    public String edgeKey(Attraction origin, Attraction destination, String preference) {
        String originId = origin == null ? "" : safe(origin.getId());
        String destinationId = destination == null ? "" : safe(destination.getId());
        String normalizedPreference = preference == null || preference.isBlank() ? "walking" : preference.trim().toLowerCase();
        return originId + "->" + destinationId + ":" + normalizedPreference;
    }

    public int finalEdgeProviderCallCount() {
        return finalEdgeProviderCalls.get();
    }

    public int cachedFinalEdgeCount() {
        return finalEdgeCache.size();
    }

    protected RouteCost requestExactRoute(Attraction origin, Attraction destination, String preference) {
        if (geoRouteGateway == null || !geoRouteGateway.isConfigured()) return null;
        finalEdgeProviderCalls.incrementAndGet();
        try {
            String city = resolveCity(origin, destination);
            RoutePairOutcome outcome = geoRouteGateway.routePair(
                    origin.getLocation(), destination.getLocation(), city,
                    queryKeyword(origin), queryKeyword(destination), preference
            );
            if (outcome == null || outcome.selected() == null
                    || outcome.selected().status() != OutcomeStatus.SUCCESS
                    || outcome.selected().segment() == null
                    || !outcome.selected().segment().metricsComplete()) {
                return null;
            }
            AmapResponseNormalizer.RouteResult segment = outcome.selected().segment();
            int distance = segment.distanceMeters() == null ? 0 : segment.distanceMeters();
            int duration = segment.durationSeconds() == null ? 0 : segment.durationSeconds();
            int walking = segment.walkingDistanceMeters() == null ? 0 : segment.walkingDistanceMeters();
            String mode = "TRANSIT".equalsIgnoreCase(segment.mode()) ? "TRANSIT" : "WALKING";
            return RouteCost.verifiedAmap(distance, duration, walking, mode);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String resolveCity(Attraction origin, Attraction destination) {
        String city = detectCityFromAttraction(origin);
        if (city != null) return city;
        city = detectCityFromAttraction(destination);
        if (city != null) return city;
        return "重庆市";
    }

    private String detectCityFromAttraction(Attraction attraction) {
        if (attraction == null) return null;
        String id = safe(attraction.getId()).toLowerCase(java.util.Locale.ROOT);
        String name = safe(attraction.getName());
        String district = safe(attraction.getDistrict());
        String location = safe(attraction.getLocation());

        if (id.startsWith("cd-") || name.contains("成都") || district.contains("成都")
                || district.matches(".*(锦江|青羊|金牛|武侯|成华|高新|天府新区|双流|郫都|温江|新都|龙泉驿|都江堰).*")) {
            return "成都市";
        }
        if (id.startsWith("xa-") || name.contains("西安") || district.contains("西安")
                || district.matches(".*(碑林|雁塔|新城|莲湖|未央|灞桥|长安|临潼).*")) {
            return "西安市";
        }
        if (id.startsWith("cq-") || name.contains("重庆") || district.contains("重庆")
                || district.matches(".*(渝中|江北|南岸|沙坪坝|九龙坡|大渡口|渝北|巴南|北碚).*")) {
            return "重庆市";
        }

        if (location.contains(",")) {
            try {
                String[] parts = location.split(",");
                double lng = Double.parseDouble(parts[0].trim());
                double lat = Double.parseDouble(parts[1].trim());
                if (lng >= 103.0 && lng <= 104.9 && lat >= 30.0 && lat <= 31.5) {
                    return "成都市";
                }
                if (lng >= 108.0 && lng <= 109.8 && lat >= 33.8 && lat <= 34.9) {
                    return "西安市";
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    public RouteCost estimateFromCoordinates(Attraction origin, Attraction destination, String preference) {
        if (origin == null || destination == null) return RouteCost.unavailable("起终点景点为空");
        double distKm = haversineDistanceKm(origin.getLocation(), destination.getLocation());
        int distanceMeters = (int) Math.round(distKm * 1000);

        String origDist = origin.getDistrict() == null ? "" : origin.getDistrict().trim();
        String destDist = destination.getDistrict() == null ? "" : destination.getDistrict().trim();
        boolean sameDistrict = !origDist.isBlank() && origDist.equals(destDist);
        boolean isFarOuterDistrict = isFarSuburban(origDist) || isFarSuburban(destDist);

        int durationMinutes;
        int walkingMeters;
        String mode;

        if (distKm <= 1.2 && !"taxi".equalsIgnoreCase(preference)) {
            mode = "WALKING";
            walkingMeters = distanceMeters;
            durationMinutes = Math.max(5, (int) Math.round(distKm / 4.5 * 60));
        } else if (sameDistrict) {
            mode = distKm <= 5.0 ? "TRANSIT" : "DRIVING";
            walkingMeters = Math.min(distanceMeters, 400);
            durationMinutes = Math.max(10, (int) Math.round((distKm / 30.0) * 60 + 10));
        } else if (isFarOuterDistrict) {
            mode = "DRIVING";
            walkingMeters = 200;
            durationMinutes = Math.max(45, (int) Math.round((distKm / 55.0) * 60 + 20));
        } else {
            mode = "TRANSIT";
            walkingMeters = 500;
            durationMinutes = Math.max(20, (int) Math.round((distKm / 22.0) * 60 + 12));
        }

        String reason = sameDistrict
                ? "同行政区（" + origDist + "）交通估算"
                : isFarOuterDistrict
                ? "主城与远郊（" + origDist + "与" + destDist + "）长途跨区交通估算"
                : "主城跨区（" + origDist + "至" + destDist + "）公共交通估算";

        return RouteCost.estimated(distanceMeters, durationMinutes, walkingMeters, mode, reason);
    }

    private String queryKeyword(Attraction attraction) {
        if (attraction == null || attraction.getAmapQuery() == null) return "";
        Object keyword = attraction.getAmapQuery().get("keywords");
        return keyword == null ? "" : String.valueOf(keyword);
    }

    private boolean isFarSuburban(String district) {
        return district.contains("涪陵") || district.contains("武隆") || district.contains("大足") || district.contains("江津");
    }

    public double haversineDistanceKm(String loc1, String loc2) {
        if (loc1 == null || loc2 == null || !loc1.contains(",") || !loc2.contains(",")) return 5.0;
        try {
            String[] p1 = loc1.split(",");
            String[] p2 = loc2.split(",");
            double lon1 = Double.parseDouble(p1[0]);
            double lat1 = Double.parseDouble(p1[1]);
            double lon2 = Double.parseDouble(p2[0]);
            double lat2 = Double.parseDouble(p2[1]);
            double latDistance = Math.toRadians(lat2 - lat1);
            double lonDistance = Math.toRadians(lon2 - lon1);
            double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                    + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                    * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            return 6371.0 * c * 1.35;
        } catch (Exception e) {
            return 5.0;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}

