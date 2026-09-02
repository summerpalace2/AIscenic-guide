package com.ai.guide.domain.planner.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结构化旅游出行约束与偏好模型
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 * 包含：天数、必去景点 (mustVisit)、同行人类型、步行耐受度、预算等级、交通偏好、饮食偏好及住宿区域。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelConstraints {
    @Builder.Default
    private String destination = "重庆";
    @Builder.Default
    private String arrivalAt = "未提供";
    @Builder.Default
    private String departureAt = "未提供";
    @Builder.Default
    private int durationDays = 2;
    @Builder.Default
    private String companions = "未提供";
    @Builder.Default
    private String walkingTolerance = "正常";
    @Builder.Default
    private String budget = "未提供";
    @Builder.Default
    private List<String> interests = new ArrayList<>(List.of("城市", "人文", "夜景"));
    @Builder.Default
    private String stayArea = "未提供";
    @Builder.Default
    private String transportPreference = "未提供";
    @Builder.Default
    private String dietPreference = "未提供";
    @Builder.Default
    private List<String> mustVisit = new ArrayList<>();
    @Builder.Default
    private List<String> avoid = new ArrayList<>();
    @Builder.Default
    private List<String> criticalMissingFields = new ArrayList<>();
    @Builder.Default
    private boolean needsClarification = false;
    @Builder.Default
    private List<ConstraintConflict> conflicts = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> shadowSemanticPreferences = new LinkedHashMap<>();
    @Builder.Default
    private String rawPrompt = "";
    /** Server-owned provenance; never accepted from a client request. */
    @JsonIgnore
    @Builder.Default
    private Map<String, ConstraintOrigin> origins = new LinkedHashMap<>();

    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("destination", destination);
        result.put("arrivalAt", arrivalAt);
        result.put("departureAt", departureAt);
        result.put("durationDays", durationDays);
        result.put("companions", companions);
        result.put("walkingTolerance", walkingTolerance);
        result.put("budget", budget);
        result.put("interests", copyList(interests));
        result.put("stayArea", stayArea);
        result.put("transportPreference", transportPreference);
        result.put("dietPreference", dietPreference);
        result.put("mustVisit", copyList(mustVisit));
        result.put("avoid", copyList(avoid));
        result.put("hardConstraints", Map.of("mustVisit", copyList(mustVisit), "avoid", copyList(avoid)));
        result.put("softPreferences", softPreferences());
        result.put("criticalMissingFields", copyList(criticalMissingFields));
        result.put("needsClarification", needsClarification);
        result.put("conflicts", copyConflicts(conflicts));
        result.put("shadowSemanticPreferences", shadowSemanticPreferences == null ? Map.of() : shadowSemanticPreferences);
        result.put("constraintProvenance", provenance());
        result.put("rawPrompt", rawPrompt);
        return result;
    }

    private List<Map<String, Object>> copyConflicts(List<ConstraintConflict> list) {
        if (list == null || list.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConstraintConflict conflict : list) {
            if (conflict != null) result.add(conflict.asMap());
        }
        return result;
    }

    private Map<String, Object> softPreferences() {
        Map<String, Object> soft = new LinkedHashMap<>();
        soft.put("companions", companions);
        soft.put("walkingTolerance", walkingTolerance);
        soft.put("budget", budget);
        soft.put("interests", copyList(interests));
        soft.put("stayArea", stayArea);
        soft.put("transportPreference", transportPreference);
        soft.put("dietPreference", dietPreference);
        return soft;
    }

    private Map<String, String> provenance() {
        List<String> fields = List.of("destination", "arrivalAt", "departureAt", "durationDays", "companions",
                "walkingTolerance", "budget", "interests", "stayArea", "transportPreference", "dietPreference",
                "mustVisit", "avoid");
        Map<String, String> result = new LinkedHashMap<>();
        for (String field : fields) result.put(field, originOf(field).name().toLowerCase());
        return result;
    }

    private List<String> copyList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public ConstraintOrigin originOf(String field) {
        if (field == null || field.isBlank() || origins == null) return ConstraintOrigin.DEFAULT;
        ConstraintOrigin origin = origins.get(field);
        return origin == null ? ConstraintOrigin.DEFAULT : origin;
    }

    public boolean canPreferenceOverride(String field) {
        ConstraintOrigin origin = originOf(field);
        return origin == ConstraintOrigin.DEFAULT || origin == ConstraintOrigin.DERIVED;
    }

    public void markOrigin(String field, ConstraintOrigin origin) {
        if (field == null || field.isBlank()) return;
        if (origins == null) origins = new LinkedHashMap<>();
        if (origin == null || origin == ConstraintOrigin.DEFAULT) origins.remove(field);
        else origins.put(field, origin);
    }

    public Map<String, ConstraintOrigin> originSnapshot() {
        if (origins == null || origins.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(origins));
    }

    public TravelConstraints copy() {
        return TravelConstraints.builder()
                .destination(destination)
                .arrivalAt(arrivalAt)
                .departureAt(departureAt)
                .durationDays(durationDays)
                .companions(companions)
                .walkingTolerance(walkingTolerance)
                .budget(budget)
                .interests(interests == null ? new ArrayList<>() : new ArrayList<>(interests))
                .stayArea(stayArea)
                .transportPreference(transportPreference)
                .dietPreference(dietPreference)
                .mustVisit(mustVisit == null ? new ArrayList<>() : new ArrayList<>(mustVisit))
                .avoid(avoid == null ? new ArrayList<>() : new ArrayList<>(avoid))
                .criticalMissingFields(criticalMissingFields == null ? new ArrayList<>() : new ArrayList<>(criticalMissingFields))
                .needsClarification(needsClarification)
                .conflicts(conflicts == null ? new ArrayList<>() : new ArrayList<>(conflicts))
                .shadowSemanticPreferences(shadowSemanticPreferences == null ? new LinkedHashMap<>() : new LinkedHashMap<>(shadowSemanticPreferences))
                .rawPrompt(rawPrompt)
                .origins(origins == null ? new LinkedHashMap<>() : new LinkedHashMap<>(origins))
                .build();
    }
}
