package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行程调整领域内部高频通用工具类，提供无状态的 JSON 行程树遍历、查找与深拷贝能力。
 */
public final class PlanAdjustmentHelper {

    private PlanAdjustmentHelper() {}

    public record StopLocation(Map<String, Object> day, List<Map<String, Object>> stops, int index, Map<String, Object> stop) {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> source, ObjectMapper objectMapper) {
        if (source == null) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(source), LinkedHashMap.class);
        } catch (Exception e) {
            return new LinkedHashMap<>(source);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> days(Map<String, Object> trip) {
        if (trip == null) return new ArrayList<>();
        return (List<Map<String, Object>>) trip.computeIfAbsent("days", k -> new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> dayStops(Map<String, Object> day) {
        if (day == null) return new ArrayList<>();
        return (List<Map<String, Object>>) day.computeIfAbsent("stops", k -> new ArrayList<>());
    }

    public static Map<String, Object> findDay(Map<String, Object> trip, int dayNumber) {
        for (Map<String, Object> d : days(trip)) {
            if (Integer.valueOf(dayNumber).equals(number(d.get("day")))) return d;
        }
        return null;
    }

    public static StopLocation locateStop(Map<String, Object> trip, String stopId) {
        if (stopId == null || stopId.isBlank()) return null;
        for (Map<String, Object> d : days(trip)) {
            List<Map<String, Object>> stops = dayStops(d);
            for (int i = 0; i < stops.size(); i++) {
                Map<String, Object> s = stops.get(i);
                if (stopId.equals(s.get("id")) || stopId.equals(s.get("stableStopId"))) {
                    return new StopLocation(d, stops, i, s);
                }
            }
        }
        return null;
    }

    public static StopLocation locateStopByVenue(Map<String, Object> trip, String venueIdOrName) {
        if (venueIdOrName == null || venueIdOrName.isBlank()) return null;
        for (Map<String, Object> d : days(trip)) {
            List<Map<String, Object>> stops = dayStops(d);
            for (int i = 0; i < stops.size(); i++) {
                Map<String, Object> s = stops.get(i);
                if (venueIdOrName.equals(s.get("venueId")) || String.valueOf(s.get("name")).contains(venueIdOrName)) {
                    return new StopLocation(d, stops, i, s);
                }
            }
        }
        return null;
    }

    public static boolean isDiningStop(Map<String, Object> stop) {
        if (stop == null) return false;
        String type = String.valueOf(stop.get("type"));
        String category = String.valueOf(stop.get("category"));
        String icon = String.valueOf(stop.get("icon"));
        String name = String.valueOf(stop.get("name"));
        String id = String.valueOf(stop.get("id"));
        return "DINING".equalsIgnoreCase(type)
                || "美食".equals(category)
                || "餐".equals(icon)
                || Boolean.TRUE.equals(stop.get("isDining"))
                || name.contains("午餐")
                || name.contains("晚餐")
                || name.contains("早餐")
                || name.contains("宵夜")
                || name.contains("茶歇")
                || id.contains("dining")
                || id.contains("lunch")
                || id.contains("dinner")
                || id.contains("tea-break")
                || id.contains("breakfast");
    }

    public static String extractDistrictFromStop(Map<String, Object> stop) {
        if (stop == null) return "渝中区";
        String direct = safe(stop.get("district"));
        if (!direct.isBlank() && !"待确认".equals(direct)) return direct;
        String addr = safe(stop.get("address"));
        for (String d : List.of("渝中区", "江北区", "南岸区", "沙坪坝区", "九龙坡区", "渝北区", "武隆区", "大足区")) {
            if (addr.contains(d) || addr.contains(d.replace("区", ""))) return d;
        }
        return "渝中区";
    }

    public static Attraction resolveStopAnchor(Map<String, Object> stop, ItineraryBuilderPort itineraryBuilder) {
        if (stop == null) return null;
        String venueId = safe(stop.get("venueId"));
        if (!venueId.isBlank() && itineraryBuilder != null) {
            Attraction attr = itineraryBuilder.attraction(venueId);
            if (attr != null) return attr;
        }
        String loc = safe(stop.get("location"));
        String name = safe(stop.get("name"));
        String dist = extractDistrictFromStop(stop);
        return Attraction.builder()
                .id(venueId.isBlank() ? "anchor-" + Math.abs(name.hashCode()) : venueId)
                .name(name)
                .district(dist)
                .location(loc.isBlank() ? "106.577000,29.556000" : loc)
                .build();
    }

    public static Attraction resolveStopAttraction(Map<String, Object> stop, ItineraryBuilderPort itineraryBuilder) {
        if (stop == null) return null;
        String venueId = safe(stop.get("venueId"));
        if (itineraryBuilder != null) {
            Attraction attr = itineraryBuilder.attraction(venueId);
            if (attr != null) return attr;
        }
        String loc = String.valueOf(stop.getOrDefault("location", ""));
        return Attraction.builder()
                .id(venueId)
                .name(String.valueOf(stop.get("name")))
                .district(extractDistrictFromStop(stop))
                .location(loc)
                .build();
    }

    public static Set<String> usedVenues(Map<String, Object> trip) {
        Set<String> set = new HashSet<>();
        for (Map<String, Object> d : days(trip)) {
            for (Map<String, Object> s : dayStops(d)) {
                if (s.get("venueId") != null) set.add(String.valueOf(s.get("venueId")));
            }
        }
        return set;
    }

    public static boolean isProtectedStop(Map<String, Object> stop,
                                          Set<String> pinned,
                                          TravelConstraints constraints) {
        if (stop != null && ("DINING".equals(stop.get("type")) || "餐".equals(stop.get("icon")))) {
            return true;
        }
        String stopId = safe(stop.get("id"));
        String stableStopId = safe(stop.get("stableStopId"));
        String venueId = safe(stop.get("venueId"));
        String name = safe(stop.get("name"));
        if (pinned != null && pinned.stream().anyMatch(selector ->
                selector.equals(stopId) || selector.equals(stableStopId) || selector.equals(venueId)
                        || (!name.isBlank() && name.contains(selector)))) {
            return true;
        }
        if (constraints == null || constraints.getMustVisit() == null) return false;
        return constraints.getMustVisit().stream().anyMatch(selector ->
                selector.equals(stopId) || selector.equals(stableStopId) || selector.equals(venueId)
                        || (!name.isBlank() && name.contains(selector)));
    }

    public static String extractStartCoordinate(Map<String, Object> draftTrip, TravelConstraints constraints) {
        if (draftTrip != null) {
            if (draftTrip.get("spatialPlan") instanceof Map<?, ?> sp) {
                Object sc = sp.get("resolvedStartCoordinate");
                if (sc != null && !String.valueOf(sc).isBlank()) {
                    return String.valueOf(sc);
                }
            }
            if (draftTrip.get("planContext") instanceof Map<?, ?> pc) {
                Object sc = pc.get("startingCoordinate");
                if (sc != null && !String.valueOf(sc).isBlank()) {
                    return String.valueOf(sc);
                }
            }
        }
        return null;
    }

    public static List<Map<?, ?>> allStops(Map<String, Object> trip) {
        if (trip == null) return List.of();
        Object daysObj = trip.get("days");
        if (!(daysObj instanceof List<?> daysList)) return List.of();
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object dayObj : daysList) {
            if (dayObj instanceof Map<?, ?> dayMap) {
                Object stopsObj = dayMap.get("stops");
                if (stopsObj instanceof List<?> stopsList) {
                    for (Object stopObj : stopsList) {
                        if (stopObj instanceof Map<?, ?> stopMap) {
                            result.add(stopMap);
                        }
                    }
                }
            }
        }
        return result;
    }

    public static String resolveTripCity(Map<String, Object> trip, StoredSession session, PlanAdjustmentIntent intent) {
        if (intent != null) {
            String venue = safeText(intent.getRequestedVenueName());
            String raw = safeText(intent.getRawMessage());
            if (venue != null && venue.contains("成都") || raw != null && raw.contains("成都")) return "成都市";
            if (venue != null && venue.contains("西安") || raw != null && raw.contains("西安")) return "西安市";
            if (venue != null && venue.contains("重庆") || raw != null && raw.contains("重庆")) return "重庆市";
        }
        if (session != null) {
            String prompt = safeText(session.prompt());
            if (prompt != null && prompt.contains("成都")) return "成都市";
            if (prompt != null && prompt.contains("西安")) return "西安市";
            if (prompt != null && prompt.contains("重庆")) return "重庆市";
            if (session.constraints() != null) {
                Object dest = session.constraints().get("destination");
                if (dest != null && !String.valueOf(dest).isBlank()) {
                    String d = String.valueOf(dest);
                    if (d.contains("成都")) return "成都市";
                    if (d.contains("西安")) return "西安市";
                    if (d.contains("重庆")) return "重庆市";
                }
            }
        }
        if (trip != null) {
            Object cityObj = trip.get("city");
            if (cityObj != null && !String.valueOf(cityObj).isBlank()) {
                String c = String.valueOf(cityObj);
                if (c.contains("成都")) return "成都市";
                if (c.contains("西安")) return "西安市";
                if (c.contains("重庆")) return "重庆市";
            }
            Object destObj = trip.get("destination");
            if (destObj != null && !String.valueOf(destObj).isBlank()) {
                String d = String.valueOf(destObj);
                if (d.contains("成都")) return "成都市";
                if (d.contains("西安")) return "西安市";
                if (d.contains("重庆")) return "重庆市";
            }
            for (Map<String, Object> day : days(trip)) {
                for (Map<String, Object> stop : dayStops(day)) {
                    String name = safeText(String.valueOf(stop.getOrDefault("name", "")));
                    String dist = safeText(String.valueOf(stop.getOrDefault("district", "")));
                    if ((name != null && name.contains("成都")) || (dist != null && (dist.contains("成都") || dist.matches(".*(锦江|青羊|金牛|武侯|成华|高新|天府新区|双流|郫都|温江|新都|龙泉驿|都江堰).*")))) {
                        return "成都市";
                    }
                    if ((name != null && name.contains("西安")) || (dist != null && (dist.contains("西安") || dist.matches(".*(碑林|雁塔|新城|莲湖|未央|灞桥|长安|临潼).*")))) {
                        return "西安市";
                    }
                }
            }
        }
        return "重庆市";
    }

    public static Integer number(Object value) {
        try {
            return value == null ? null : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    public static String safeText(String value) {
        if (value == null || value.isBlank()) return null;
        return value.trim();
    }

    public static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
