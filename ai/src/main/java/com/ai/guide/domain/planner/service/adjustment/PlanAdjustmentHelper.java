package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.planner.service.AttractionDiningKnowledge;
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
        Object d = trip.get("days");
        if (d instanceof List<?>) {
            return (List<Map<String, Object>>) d;
        }
        try {
            return (List<Map<String, Object>>) trip.computeIfAbsent("days", k -> new ArrayList<>());
        } catch (UnsupportedOperationException e) {
            return new ArrayList<>();
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> dayStops(Map<String, Object> day) {
        if (day == null) return new ArrayList<>();
        Object s = day.get("stops");
        if (s instanceof List<?>) {
            return (List<Map<String, Object>>) s;
        }
        try {
            return (List<Map<String, Object>>) day.computeIfAbsent("stops", k -> new ArrayList<>());
        } catch (UnsupportedOperationException e) {
            return new ArrayList<>();
        }
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

    public static boolean isDiningStop(Map<String, Object> stop, com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort taxonomy) {
        if (stop == null) return false;
        String type = String.valueOf(stop.get("type"));
        String category = String.valueOf(stop.get("category"));
        String icon = String.valueOf(stop.get("icon"));
        String name = String.valueOf(stop.get("name"));
        String id = String.valueOf(stop.get("id"));
        boolean mealName = List.of("slotBreakfast", "slotLunch", "slotDinner", "slotNightSnack", "slotTeaBreak")
                .stream().flatMap(key -> taxonomy.getHints(key).stream()).anyMatch(name::contains);
        return "DINING".equalsIgnoreCase(type)
                || taxonomy.getHints("diningStopCategories").stream().anyMatch(category::equals)
                || taxonomy.getHints("diningStopIcons").stream().anyMatch(icon::equals)
                || Boolean.TRUE.equals(stop.get("isDining"))
                || mealName
                || id.contains("dining")
                || id.contains("lunch")
                || id.contains("dinner")
                || id.contains("tea-break")
                || id.contains("breakfast");
    }

    /** Compatibility predicate for callers without a taxonomy dependency; relies on structured fields and ID contract only. */
    public static boolean isDiningStop(Map<String, Object> stop) {
        return isDiningStop(stop, com.ai.guide.domain.taxonomy.gateway.TaxonomyPort.empty());
    }

    public static String extractDistrictFromStop(Map<String, Object> stop) {
        if (stop == null) return "";
        String direct = safe(stop.get("district"));
        if (!direct.isBlank() && !"待确认".equals(direct)) return direct;
        return "";
    }

    public static com.ai.guide.domain.dining.model.DiningOption verifiedDiningOption(DiningVenue venue, String slotType,
                                                                                     com.ai.guide.domain.planner.engine.DiningCategoryGrounder grounder) {
        if (venue == null || safe(venue.getId()).isBlank() || safe(venue.getName()).isBlank()
                || !isValidCoordinate(venue.getLocation())) return null;
        String prefix = grounder == null ? "" : grounder.mealDisplayPrefix(slotType);
        return new com.ai.guide.domain.dining.model.DiningOption(venue.getId(), prefix + venue.getName(),
                venue.getSpecialtyDish(), venue.getCategory(), venue.getAverageCost(), venue.getDuration(),
                venue.getDistanceDesc(), venue.getSummary(), venue.getRecommendationReason(), venue.getTone(), venue.getLocation());
    }

    public static boolean matchesRequestedDiningVenue(String requestedName, String actualName) {
        String requested = safe(requestedName).replaceAll("^【[^】]+】", "").replaceAll("[\\p{Punct}\\p{Z}\\p{IsPunctuation}]+", "");
        String actual = safe(actualName).replaceAll("^【[^】]+】", "").replaceAll("[\\p{Punct}\\p{Z}\\p{IsPunctuation}]+", "");
        return requested.length() >= 2 && actual.length() >= 2
                && (actual.contains(requested) || requested.contains(actual));
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
                .location(isValidCoordinate(loc) ? loc : null)
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
        // Structured itinerary/session data is authoritative; restaurant names are not city selectors.
        if (trip != null) {
            String city = firstNonBlank(safe(trip.get("city")), safe(trip.get("destination")));
            if (city != null) return city;
            if (trip.get("constraints") instanceof Map<?, ?> values) {
                city = safeText(safe(values.get("destination")));
                if (city != null) return city;
            }
        }
        if (session != null && session.constraints() != null) {
            return safeText(safe(session.constraints().get("destination")));
        }
        return null;
    }

    public static String resolveTripCity(Map<String, Object> trip, TravelConstraints constraints) {
        String city = resolveTripCity(trip, (StoredSession) null, null);
        return city != null ? city : (constraints == null ? null : safeText(constraints.getDestination()));
    }

    public static boolean isValidCoordinate(String location) {
        return com.ai.guide.domain.geo.model.GeoCoordinateRules.isValid(location);
    }

    public static String resolveDefaultCoordinate(Map<String, Object> draftTrip, TravelConstraints constraints,
                                                   List<Map<String, Object>> stops, double maxCentroidSpanMeters) {
        if (stops != null && !stops.isEmpty()) {
            double sumLng = 0;
            double sumLat = 0;
            String lastValidLoc = null;
            double lastLng = 0;
            double lastLat = 0;
            double maxDistanceFromLastMeters = 0;
            int count = 0;

            for (Map<String, Object> s : stops) {
                String loc = safe(s.get("location"));
                if (isValidCoordinate(loc)) {
                    try {
                        String[] parts = loc.split(",");
                        double lng = Double.parseDouble(parts[0].trim());
                        double lat = Double.parseDouble(parts[1].trim());
                        // 严防 NaN 与超出经纬度物理边界的毒丸数据
                        if (!Double.isNaN(lng) && !Double.isNaN(lat)
                                && !Double.isInfinite(lng) && !Double.isInfinite(lat)
                                && lng >= -180.0 && lng <= 180.0 && lat >= -90.0 && lat <= 90.0) {
                            sumLng += lng;
                            sumLat += lat;
                            if (lastValidLoc != null) {
                                maxDistanceFromLastMeters = Math.max(maxDistanceFromLastMeters,
                                        haversineMeters(lng, lat, lastLng, lastLat));
                            }
                            lastValidLoc = String.format(java.util.Locale.ROOT, "%.6f,%.6f", lng, lat);
                            lastLng = lng;
                            lastLat = lat;
                            count++;
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (count > 0) {
                // A wide route span makes the arithmetic centroid a poor search anchor; use the latest visit instead.
                if (maxDistanceFromLastMeters > Math.max(0, maxCentroidSpanMeters) && lastValidLoc != null) {
                    return lastValidLoc;
                }
                return String.format(java.util.Locale.ROOT, "%.6f,%.6f", sumLng / count, sumLat / count);
            }
        }
        String start = extractStartCoordinate(draftTrip, constraints);
        return isValidCoordinate(start) ? start : null;
    }

    private static double haversineMeters(double lng1, double lat1, double lng2, double lat2) {
        double latDelta = Math.toRadians(lat2 - lat1);
        double lngDelta = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDelta / 2) * Math.sin(lngDelta / 2);
        double safeA = Math.max(0, Math.min(1, a));
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(safeA), Math.sqrt(1 - safeA));
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
