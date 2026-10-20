package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.geo.model.GeoPoiCandidate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Shared exact-identity rules for catalog geocoding and itinerary hydration. */
public final class AttractionPoiIdentityMatcher {
    private AttractionPoiIdentityMatcher() { }

    /** Only canonical catalog names and explicitly configured exact names identify a POI. */
    public static List<String> acceptableExactNames(Attraction attraction) {
        if (attraction == null) return List.of();
        LinkedHashSet<String> names = new LinkedHashSet<>();
        add(names, attraction.getName());
        Map<String, Object> query = attraction.getAmapQuery();
        if (query != null) {
            addValues(names, query.get("matches"));
            addValues(names, query.get("keywords"));
            addValues(names, query.get("alternatives"));
        }
        return List.copyOf(names);
    }

    public static boolean isValidExactIdentity(GeoPoiCandidate candidate, List<String> acceptedNames, String city) {
        if (candidate == null || candidate.poiId() == null || candidate.poiId().isBlank()
                || candidate.name() == null || candidate.name().isBlank()
                || candidate.coordinate() == null || !candidate.coordinate().isValid()
                || city == null || city.isBlank()
                || candidate.cityName() == null || candidate.cityName().isBlank()) return false;
        if (!normalizeCity(candidate.cityName()).equals(normalizeCity(city))) return false;
        String actualName = candidate.name().trim();
        return acceptedNames != null && acceptedNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .anyMatch(name -> name.trim().equalsIgnoreCase(actualName));
    }

    /** Normalize the administrative suffix only; never infer identity from a POI name prefix. */
    public static String normalizeCity(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.endsWith("市") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static void addValues(LinkedHashSet<String> result, Object value) {
        if (value instanceof Iterable<?> values) {
            values.forEach(item -> add(result, item == null ? null : String.valueOf(item)));
        } else if (value instanceof Object[] values) {
            for (Object item : values) add(result, item == null ? null : String.valueOf(item));
        } else if (value != null) {
            add(result, String.valueOf(value));
        }
    }

    private static void add(LinkedHashSet<String> result, String value) {
        if (value != null && !value.isBlank()) result.add(value.trim());
    }
}
