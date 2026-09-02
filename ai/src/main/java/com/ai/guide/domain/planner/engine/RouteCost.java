package com.ai.guide.domain.planner.engine;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站点间路线通行成本与耗时模型
 *
 * 所属领域：domain.planner.engine（规划引擎核心算法层）
 * 架构职责：封装起点到终点的通行方式（步行/公交/驾车）、实际测算距离（米）、耗时（Duration）及数据置信度状态。
 */
public record RouteCost(
        Duration duration,
        int distanceMeters,
        int walkingMeters,
        String transportMode,
        RouteDataStatus status,
        String reason
) {
    public enum RouteDataStatus {
        VERIFIED_AMAP,
        CACHED,
        ESTIMATED,
        UNAVAILABLE
    }

    public static RouteCost estimated(int distanceMeters, int durationMinutes, int walkingMeters, String mode, String reason) {
        return new RouteCost(
                Duration.ofMinutes(Math.max(0, durationMinutes)),
                Math.max(0, distanceMeters),
                Math.max(0, walkingMeters),
                mode == null ? "TRANSIT" : mode,
                RouteDataStatus.ESTIMATED,
                reason == null ? "根据地理坐标与行政区路网估算" : reason
        );
    }

    public static RouteCost cached(int distanceMeters, int durationSeconds, int walkingMeters, String mode) {
        return new RouteCost(
                Duration.ofSeconds(Math.max(0, durationSeconds)),
                Math.max(0, distanceMeters),
                Math.max(0, walkingMeters),
                mode == null ? "TRANSIT" : mode,
                RouteDataStatus.CACHED,
                "来自已核验高德路线缓存"
        );
    }

    public static RouteCost verifiedAmap(int distanceMeters, int durationSeconds, int walkingMeters, String mode) {
        return new RouteCost(
                Duration.ofSeconds(Math.max(0, durationSeconds)),
                Math.max(0, distanceMeters),
                Math.max(0, walkingMeters),
                mode == null ? "TRANSIT" : mode,
                RouteDataStatus.VERIFIED_AMAP,
                "来自高德实时路径规划接口核验"
        );
    }

    public static RouteCost unavailable(String reason) {
        return new RouteCost(
                Duration.ZERO,
                0,
                0,
                "UNAVAILABLE",
                RouteDataStatus.UNAVAILABLE,
                reason == null ? "路线服务不可用" : reason
        );
    }

    public RouteCost asCached() {
        return new RouteCost(duration, distanceMeters, walkingMeters, transportMode,
                RouteDataStatus.CACHED, "来自本次或既有已核验路线缓存");
    }

    public Map<String, Object> asMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("durationMinutes", duration == null ? 0L : duration.toMinutes());
        map.put("durationSeconds", duration == null ? 0L : duration.toSeconds());
        map.put("distanceMeters", distanceMeters);
        map.put("walkingMeters", walkingMeters);
        map.put("transportMode", transportMode);
        map.put("routeDataStatus", status == null ? RouteDataStatus.UNAVAILABLE.name() : status.name());
        map.put("reason", reason == null ? "" : reason);
        return map;
    }

    public static RouteCost fromMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        try {
            long seconds = number(raw.get("durationSeconds"));
            if (seconds <= 0) seconds = number(raw.get("durationMinutes")) * 60;
            int distance = (int) number(raw.get("distanceMeters"));
            int walking = (int) number(raw.get("walkingMeters"));
            String mode = text(raw.get("transportMode"));
            String statusText = text(raw.get("routeDataStatus"));
            RouteDataStatus status = statusText.isBlank() ? RouteDataStatus.UNAVAILABLE : RouteDataStatus.valueOf(statusText);
            return new RouteCost(Duration.ofSeconds(Math.max(0, seconds)), Math.max(0, distance), Math.max(0, walking),
                    mode.isBlank() ? "UNAVAILABLE" : mode, status, text(raw.get("reason")));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long number(Object value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
