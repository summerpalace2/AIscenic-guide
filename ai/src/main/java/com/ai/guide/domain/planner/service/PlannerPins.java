package com.ai.guide.domain.planner.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Persistent station protection lives in the versioned trip snapshot. */
public final class PlannerPins {
    private PlannerPins() { }
    public static final String KEY = "pinnedStopIds";

    public static List<String> read(Map<String, Object> trip) {
        var ids = new LinkedHashSet<String>();
        if (trip != null && trip.get(KEY) instanceof List<?> values)
            for (Object value : values) if (value instanceof String id && !id.isBlank()) ids.add(id);
        return new ArrayList<>(ids);
    }

    public static Map<String, Map<String, Object>> stops(Map<String, Object> trip) {
        var result = new LinkedHashMap<String, Map<String, Object>>();
        if (trip != null && trip.get("days") instanceof List<?> days)
            for (Object day : days) if (day instanceof Map<?, ?> d && d.get("stops") instanceof List<?> stops)
                for (Object item : stops) if (item instanceof Map<?, ?> stop && stop.get("id") instanceof String id) {
                    var copy = new LinkedHashMap<String, Object>();
                    stop.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    copy.put("_pinDay", d.get("day"));
                    result.put(id, copy);
                }
        return result;
    }

    public static List<String> delta(Map<String, Object> trip, List<String> add, List<String> remove) {
        var available = stops(trip).keySet();
        var additions = add == null ? List.<String>of() : add;
        var removals = remove == null ? List.<String>of() : remove;
        for (String id : additions) if (id == null || !available.contains(id))
            throw new IllegalArgumentException("固定目标不属于当前行程，请刷新后重试。");
        for (String id : removals) if (id == null || !available.contains(id))
            throw new IllegalArgumentException("取消固定目标不属于当前行程，请刷新后重试。");
        if (additions.stream().anyMatch(removals::contains))
            throw new IllegalArgumentException("同一站点不能同时固定和取消固定。");
        var result = new LinkedHashSet<>(read(trip));
        result.addAll(additions);
        result.removeAll(removals);
        return new ArrayList<>(result);
    }

    /** Schedule times may change, but a pinned station cannot disappear or become another venue. */
    public static boolean retained(Map<String, Object> before, Map<String, Object> after) {
        var current = stops(before);
        var next = stops(after);
        for (String id : read(before)) {
            var original = current.get(id);
            var candidate = next.get(id);
            if (original == null || candidate == null) return false;
            for (String field : List.of("venueId", "name", "_pinDay"))
                if (!Objects.equals(original.get(field), candidate.get(field))) return false;
        }
        return true;
    }

    /** Restoring drafts or applying proposals cannot alter the authoritative protection set. */
    public static void preserve(Map<String, Object> before, Map<String, Object> after) {
        if (before.containsKey(KEY) || after.containsKey(KEY)) after.put(KEY, read(before));
    }
}
