package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.gateway.DynamicShortTripPolicyPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoRoutePairOutcome;
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
    private final DynamicShortTripPolicyPort dynamicPolicy;
    private final Map<String, RouteCost> finalEdgeCache = new ConcurrentHashMap<>();
    private final AtomicInteger finalEdgeProviderCalls = new AtomicInteger();

    @Autowired
    public RouteCostProvider(@Autowired(required = false) GeoRouteGatewayPort geoRouteGateway,
                             @Autowired(required = false) PlannerMetricsLogger metricsLogger,
                             @Autowired(required = false) DynamicShortTripPolicyPort dynamicPolicy) {
        this.geoRouteGateway = geoRouteGateway;
        this.metricsLogger = metricsLogger != null ? metricsLogger : new PlannerMetricsLogger();
        this.dynamicPolicy = dynamicPolicy == null ? DynamicShortTripPolicyPort::unconfigured : dynamicPolicy;
    }

    public RouteCostProvider(GeoRouteGatewayPort geoRouteGateway) {
        this(geoRouteGateway, new PlannerMetricsLogger(), null);
    }

    public RouteCostProvider(GeoRouteGatewayPort geoRouteGateway, DynamicShortTripPolicyPort dynamicPolicy) {
        this(geoRouteGateway, new PlannerMetricsLogger(), dynamicPolicy);
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
        if (origin.getId() != null && origin.getId().equals(destination.getId())) {
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
        if (origin.getId() != null && origin.getId().equals(destination.getId())) {
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
        return originId + "@" + (origin == null ? "" : safe(origin.getLocation()))
                + "->" + destinationId + "@" + (destination == null ? "" : safe(destination.getLocation()))
                + ":" + normalizedPreference;
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
            GeoRoutePairOutcome outcome = geoRouteGateway.routePair(
                    origin.getLocation(), destination.getLocation(), city,
                    queryKeyword(origin), queryKeyword(destination), preference
            );
            if (outcome == null || outcome.selected() == null
                    || outcome.selected().status() != GeoOutcomeStatus.SUCCESS
                    || outcome.selected().segment() == null
                    || !outcome.selected().segment().metricsComplete()) {
                return null;
            }
            com.ai.guide.domain.geo.model.GeoRouteSegment segment = outcome.selected().segment();
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
        String city=detectCityFromAttraction(origin);
        return city != null ? city : detectCityFromAttraction(destination);
    }

    private String detectCityFromAttraction(Attraction attraction) {
        if (attraction == null) return null;
        String catalogCity = safe(attraction.getCity());
        if (!catalogCity.isBlank()) return catalogCity;
        if (attraction.getAmapQuery() == null) return null;
        String city=safe(String.valueOf(attraction.getAmapQuery().getOrDefault("city","")));
        return city.isBlank() ? null : city;
    }

    public RouteCost estimateFromCoordinates(Attraction origin, Attraction destination, String preference) {
        if (origin == null || destination == null) return RouteCost.unavailable("起终点景点为空");
        double rawDistanceKm = haversineDistanceKm(origin.getLocation(), destination.getLocation());
        if (!Double.isFinite(rawDistanceKm)) return RouteCost.unavailable("起终点坐标缺失或无效");
        DynamicShortTripPolicyPort.RouteFallbackPolicy policy = dynamicPolicy.getPolicy().routeFallbackPolicy();
        if (policy == null || !policy.configured()) return RouteCost.unavailable("路线估算策略未配置");
        double distKm = rawDistanceKm * policy.distanceSafetyMultiplier();
        int distanceMeters = (int) Math.round(distKm * 1000);

        String origDist = origin.getDistrict() == null ? "" : origin.getDistrict().trim();
        String destDist = destination.getDistrict() == null ? "" : destination.getDistrict().trim();
        boolean sameDistrict = !origDist.isBlank() && origDist.equals(destDist);
        String originCity = safe(detectCityFromAttraction(origin));
        String destinationCity = safe(detectCityFromAttraction(destination));
        if (!originCity.isBlank() && !destinationCity.isBlank()
                && !normalizeCity(originCity).equals(normalizeCity(destinationCity)))
            return RouteCost.unavailable("跨城市路线需实时路线服务核验");
        boolean isFarOuterDistrict = distKm > policy.farDistrictThresholdKm();

        int durationMinutes;
        int walkingMeters;
        String mode;

        if (distKm <= policy.nearbyDistanceKm() && !"taxi".equalsIgnoreCase(preference)) {
            // Coordinates prove proximity, never pedestrian reachability across water,
            // fences or inaccessible terrain. Only provider-backed paths may assert WALKING.
            return RouteCost.estimated(distanceMeters, Math.max(policy.nearbyMinimumMinutes(),
                            (int) Math.ceil(distKm / policy.nearbySpeedKmh() * 60)),
                    distanceMeters, "TRANSIT", "交通估算，路线待核验：仅有直线距离，不能确认步行可达；已保守预留交通时间");
        } else if (sameDistrict) {
            mode = distKm <= policy.sameDistrictTransitMaximumKm() ? "TRANSIT" : "DRIVING";
            walkingMeters = Math.min(distanceMeters, policy.sameDistrictWalkingMeters());
            durationMinutes = Math.max(policy.sameDistrictMinimumMinutes(), (int) Math.round(
                    (distKm / policy.sameDistrictSpeedKmh()) * 60 + policy.sameDistrictOverheadMinutes()));
        } else if (isFarOuterDistrict) {
            mode = "DRIVING";
            walkingMeters = policy.farDistrictWalkingMeters();
            durationMinutes = Math.max(policy.farDistrictMinimumMinutes(), (int) Math.round(
                    (distKm / policy.farDistrictSpeedKmh()) * 60 + policy.farDistrictOverheadMinutes()));
        } else {
            mode = "TRANSIT";
            walkingMeters = policy.crossDistrictWalkingMeters();
            durationMinutes = Math.max(policy.crossDistrictMinimumMinutes(), (int) Math.round(
                    (distKm / policy.crossDistrictSpeedKmh()) * 60 + policy.crossDistrictOverheadMinutes()));
        }

        String reason = sameDistrict
                ? "同辖区坐标交通估算，路线待核验"
                : isFarOuterDistrict
                ? "远距离坐标交通估算，路线待核验"
                : "跨辖区坐标交通估算，路线待核验";

        return RouteCost.estimated(distanceMeters, durationMinutes, walkingMeters, mode, reason);
    }

    private String queryKeyword(Attraction attraction) {
        if (attraction == null || attraction.getAmapQuery() == null) return "";
        Object keyword = attraction.getAmapQuery().get("keywords");
        return keyword == null ? "" : String.valueOf(keyword);
    }

    public double haversineDistanceKm(String loc1, String loc2) {
        if (loc1 == null || loc2 == null || !loc1.contains(",") || !loc2.contains(",")) return Double.POSITIVE_INFINITY;
        try {
            String[] p1 = loc1.split(",");
            String[] p2 = loc2.split(",");
            double lon1 = Double.parseDouble(p1[0]);
            double lat1 = Double.parseDouble(p1[1]);
            double lon2 = Double.parseDouble(p2[0]);
            double lat2 = Double.parseDouble(p2[1]);
            if (!Double.isFinite(lon1) || !Double.isFinite(lon2) || !Double.isFinite(lat1) || !Double.isFinite(lat2)
                    || Math.abs(lon1) > 180 || Math.abs(lon2) > 180 || Math.abs(lat1) > 90 || Math.abs(lat2) > 90)
                return Double.POSITIVE_INFINITY;
            double latDistance = Math.toRadians(lat2 - lat1);
            double lonDistance = Math.toRadians(lon2 - lon1);
            double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                    + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                    * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
            a = Math.max(0.0, Math.min(1.0, a));
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            return 6371.0 * c;
        } catch (Exception e) {
            return Double.POSITIVE_INFINITY;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeCity(String city) {
        return safe(city).replaceAll("市$", "");
    }
}

