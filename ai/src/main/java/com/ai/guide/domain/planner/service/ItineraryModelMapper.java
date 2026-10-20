package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.category.PoiDomainType;
import com.ai.guide.domain.planner.model.itinerary.ItineraryDay;
import com.ai.guide.domain.planner.model.itinerary.ItineraryStop;
import com.ai.guide.domain.planner.model.itinerary.ItineraryTrip;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.util.*;

/**
 * 强类型领域模型与无类型字典结构双向保真映射器 (Model Mapper)
 */
@Component
public class ItineraryModelMapper {

    private PlannerTaxonomyPort taxonomy = TaxonomyPort.empty();

    @Autowired(required = false)
    public void setTaxonomy(PlannerTaxonomyPort taxonomy) {
        if (taxonomy != null) this.taxonomy = taxonomy;
    }

    /**
     * 将无类型 Map 结构转换为强类型聚合根 ItineraryTrip
     */
    @SuppressWarnings("unchecked")
    public ItineraryTrip fromMap(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return null;

        String tripId = safeString(raw.get("formalTripId"), safeString(raw.get("tripId"), "trip-" + UUID.randomUUID()));
        String title = safeString(raw.get("title"), "定制旅行方案");
        int version = 1;
        if (raw.get("version") instanceof Number n) {
            version = n.intValue();
        } else if (raw.get("version") instanceof String s) {
            try { version = Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        String city = safeString(raw.get("city"), "");

        List<ItineraryDay> days = new ArrayList<>();
        Object daysObj = raw.get("days");
        if (daysObj instanceof List<?> dayList) {
            for (Object dObj : dayList) {
                if (dObj instanceof Map<?, ?> dMap) {
                    days.add(parseDay((Map<String, Object>) dMap));
                }
            }
        }

        return new ItineraryTrip(tripId, title, version, city, null, days, null, new LinkedHashMap<>(raw), null, null);
    }

    /**
     * 将强类型 ItineraryTrip 转换为完全兼容前端与旧版存储的 Map<String, Object>
     */
    public Map<String, Object> toMap(ItineraryTrip trip) {
        if (trip == null) return Collections.emptyMap();

        Map<String, Object> root = new LinkedHashMap<>();
        if (trip.extensionAttributes() != null) {
            root.putAll(trip.extensionAttributes());
        }

        root.put("formalTripId", trip.tripId());
        root.put("title", trip.title());
        root.put("version", trip.version());
        root.put("city", trip.destinationCity());

        List<Map<String, Object>> dayMaps = new ArrayList<>();
        if (trip.days() != null) {
            for (ItineraryDay day : trip.days()) {
                dayMaps.add(dayToMap(day));
            }
        }
        root.put("days", dayMaps);
        return root;
    }

    @SuppressWarnings("unchecked")
    private ItineraryDay parseDay(Map<String, Object> dMap) {
        int dayNum = 1;
        if (dMap.get("day") instanceof Number n) {
            dayNum = n.intValue();
        } else if (dMap.get("day") instanceof String s) {
            try { dayNum = Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }

        String dateLabel = safeString(dMap.get("dateLabel"), "第 " + dayNum + " 天");
        String theme = safeString(dMap.get("theme"), "特色游览");
        String departure = safeString(dMap.get("departureContext"), "");
        String meal = safeString(dMap.get("mealGuidance"), "");

        List<ItineraryStop> stops = new ArrayList<>();
        Object stopsObj = dMap.get("stops");
        if (stopsObj instanceof List<?> stopList) {
            for (Object sObj : stopList) {
                if (sObj instanceof Map<?, ?> sMap) {
                    stops.add(parseStop((Map<String, Object>) sMap));
                }
            }
        }

        Map<String, Object> extraAttributes = new LinkedHashMap<>(dMap);
        return new ItineraryDay(dayNum, null, dateLabel, theme, departure, meal, stops, extraAttributes);
    }

    private ItineraryStop parseStop(Map<String, Object> sMap) {
        String id = safeString(sMap.get("id"), "stop-" + UUID.randomUUID());
        String stableId = safeString(sMap.get("stableStopId"), id);
        String venueId = safeString(sMap.get("venueId"), id);
        String name = safeString(sMap.get("name"), "未命名点位");
        String district = safeString(sMap.get("district"), "");
        String typeCode = safeString(sMap.get("type"), "SCENIC");
        PoiDomainType type = PoiDomainType.fromCode(typeCode);

        String timeStr = safeString(sMap.get("time"), "");
        LocalTime time = parseTime(timeStr);
        String durStr = safeString(sMap.get("duration"), "");
        Duration duration = parseDuration(durStr);

        boolean pinned = Boolean.TRUE.equals(sMap.get("pinned")) || Boolean.TRUE.equals(sMap.get("isPinned"));
        String hint = safeString(sMap.get("topographyTransitHint"), "");

        return new ItineraryStop(
                id, stableId, venueId, name, name, district, type,
                safeString(sMap.get("category"), taxonomy.getHints("defaultStopCategory").stream().findFirst().orElse("")),
                safeString(sMap.get("icon"), type.getDefaultIcon()),
                time, duration,
                safeString(sMap.get("location"), ""),
                safeString(sMap.get("summary"), ""),
                safeString(sMap.get("detail"), ""),
                safeString(sMap.get("aiGuide"), ""),
                pinned, null, null, hint, Collections.emptyList(), new LinkedHashMap<>(sMap)
        );
    }

    private Map<String, Object> dayToMap(ItineraryDay day) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (day.extraAttributes() != null) {
            map.putAll(day.extraAttributes());
        }
        map.put("day", day.dayNumber());
        map.put("dateLabel", day.dateLabel());
        map.put("theme", day.theme());
        map.put("departureContext", day.departureContext());
        map.put("mealGuidance", day.mealGuidance());

        List<Map<String, Object>> stops = new ArrayList<>();
        if (day.stops() != null) {
            for (ItineraryStop stop : day.stops()) {
                stops.add(stopToMap(stop));
            }
        }
        map.put("stops", stops);
        return map;
    }

    private Map<String, Object> stopToMap(ItineraryStop stop) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (stop.extraAttributes() != null) {
            map.putAll(stop.extraAttributes());
        }
        map.put("id", stop.id());
        map.put("stableStopId", stop.stableStopId());
        map.put("venueId", stop.venueId());
        map.put("name", stop.name());
        map.put("district", stop.district());
        map.put("type", stop.type().getCode());
        map.put("icon", stop.icon());
        map.put("time", stop.formattedTime());
        map.put("duration", stop.formattedDuration());
        map.put("pinned", stop.isPinned());
        if (stop.topographyTransitHint() != null && !stop.topographyTransitHint().isBlank()) {
            map.put("topographyTransitHint", stop.topographyTransitHint());
            map.put("trafficHint", stop.topographyTransitHint());
        }
        return map;
    }

    private String safeString(Object val, String defaultVal) {
        if (val == null) return defaultVal;
        String s = String.valueOf(val).trim();
        return (s.isEmpty() || "null".equalsIgnoreCase(s)) ? defaultVal : s;
    }

    private LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank() || "待安排".equals(raw)) return null;
        try {
            String t = raw.trim();
            if (t.matches("\\d{1,2}:\\d{2}")) {
                String[] parts = t.split(":");
                return LocalTime.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
            }
            if (t.length() >= 5) {
                return LocalTime.parse(t.substring(0, 5));
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Duration parseDuration(String raw) {
        if (raw == null || raw.isBlank() || "待安排".equals(raw)) return null;
        try {
            int hours = 0;
            int minutes = 0;
            java.util.regex.Matcher hm = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:个)?小时").matcher(raw);
            if (hm.find()) {
                double h = Double.parseDouble(hm.group(1));
                hours = (int) (h * 60);
            }
            java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(\\d+)\\s*分钟").matcher(raw);
            if (mm.find()) {
                minutes = Integer.parseInt(mm.group(1));
            }
            if (hours > 0 || minutes > 0) {
                return Duration.ofMinutes(hours + minutes);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(raw);
            if (m.find()) {
                double val = Double.parseDouble(m.group(1));
                if (raw.contains("小时")) return Duration.ofMinutes((long) (val * 60));
                return Duration.ofMinutes((long) val);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
