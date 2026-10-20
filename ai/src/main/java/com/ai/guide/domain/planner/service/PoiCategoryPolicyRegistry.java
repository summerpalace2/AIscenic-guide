package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.category.PoiCategoryPolicy;
import com.ai.guide.domain.planner.model.category.PoiDomainType;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

/**
 * 垂直业态策略注册中心 (Policy Registry)
 */
@Component
public class PoiCategoryPolicyRegistry {

    private final Map<PoiDomainType, PoiCategoryPolicy> policies = new EnumMap<>(PoiDomainType.class);
    private final PlannerTaxonomyPort taxonomy;

    @Autowired
    public PoiCategoryPolicyRegistry(PlannerTaxonomyPort taxonomy) {
        this.taxonomy = Objects.requireNonNull(taxonomy, "taxonomy");
        taxonomy.getCategoryPolicies().forEach((key, source) -> {
            PoiDomainType category = PoiDomainType.valueOf(key.toUpperCase(Locale.ROOT));
            List<PoiCategoryPolicy.TimeWindow> windows = source.preferredTimeWindows().stream()
                    .map(w -> new PoiCategoryPolicy.TimeWindow(LocalTime.of(w.startMinute() / 60, w.startMinute() % 60),
                            LocalTime.of(w.endMinute() / 60, w.endMinute() % 60), w.label())).toList();
            policies.put(category, new DefaultPolicy(category, source.minDurationMinutes(), source.maxDurationMinutes(),
                    source.defaultDurationMinutes(), windows, source.allowedAsFirstStop(), source.allowedAsLastStop(),
                    source.maxOccurrencesPerDay(), source.minIntervalMinutesBetweenSameCategory()));
        });
        if (policies.size() != PoiDomainType.values().length)
            throw new IllegalStateException("Taxonomy must define a policy for every POI domain type");
    }

    public PoiCategoryPolicy getPolicy(PoiDomainType type) {
        if (type == null) return policies.get(PoiDomainType.SCENIC);
        return policies.getOrDefault(type, policies.get(PoiDomainType.SCENIC));
    }

    /**
     * 判断一个站点 Map 是否属于餐饮节点（向后兼容通用方法）
     */
    public boolean isDiningStop(Map<?, ?> stop) {
        if (stop == null) return false;
        String typecode = stop.get("typecode") == null ? "" : String.valueOf(stop.get("typecode")).trim();
        if (AmapPoiTaxonomy.isValidCode(typecode)) return typecode.startsWith("05");
        if (typecode.startsWith("05")) return true;
        if (typecode.startsWith("10") || typecode.startsWith("11") || typecode.startsWith("14")
                || typecode.startsWith("15") || typecode.startsWith("06") || typecode.startsWith("0806")) return false;
        String type = String.valueOf(stop.get("type"));
        if ("DINING".equalsIgnoreCase(type)) return true;
        Object isDiningObj = stop.get("isDining");
        if (Boolean.TRUE.equals(isDiningObj) || "true".equalsIgnoreCase(String.valueOf(isDiningObj))) return true;
        String name = String.valueOf(stop.get("name"));
        String venueId = String.valueOf(stop.get("venueId"));
        return taxonomy.getHints("diningSlotNames").stream().anyMatch(name::contains)
                || venueId.startsWith("dining-") || venueId.startsWith("lunch-") || venueId.startsWith("dinner-");
    }

    /**
     * 解析站点 Map 对应的业态类型
     */
    public PoiDomainType resolveCategory(Map<?, ?> stop) {
        if (stop == null) return PoiDomainType.SCENIC;

        // Provider categories take precedence over name heuristics. Scenic is 11,
        // whereas transport facilities are 15 in the AMap taxonomy.
        String typecode = stop.get("typecode") == null ? "" : String.valueOf(stop.get("typecode")).trim();
        if (!typecode.isBlank()) {
            if (typecode.startsWith("10")) return PoiDomainType.HOTEL;
            if (typecode.startsWith("0806")) return PoiDomainType.THEATER;
            if (typecode.startsWith("06")) return PoiDomainType.SHOPPING;
            if (AmapPoiTaxonomy.isScenicVenue(typecode) || AmapPoiTaxonomy.isCulturalVenue(typecode)) return PoiDomainType.SCENIC;
            if (typecode.startsWith("15")) return PoiDomainType.TRANSIT;
            if (typecode.startsWith("05")) return PoiDomainType.DINING;
            // An unsupported provider category must not become dining merely
            // because its name mentions food. Scenic eligibility is checked separately.
            if (AmapPoiTaxonomy.isValidCode(typecode)) return PoiDomainType.SCENIC;
        }
        if (isDiningStop(stop)) return PoiDomainType.DINING;

        // 2. 降级容错：根据名称语义特征识别
        String type = String.valueOf(stop.get("type"));
        String name = String.valueOf(stop.get("name"));
        if (containsAny(name, "dining") || containsAny(type, "diningType")) return PoiDomainType.DINING;
        if (containsAny(name, "theater")) return PoiDomainType.THEATER;
        if (containsAny(name, "shopping")) return PoiDomainType.SHOPPING;
        if (containsAny(name, "transit")) return PoiDomainType.TRANSIT;
        if (containsAny(name, "hotel")) return PoiDomainType.HOTEL;
        return PoiDomainType.fromCode(type);
    }

    private boolean containsAny(String text, String hintGroup) {
        return text != null && taxonomy.getHints(hintGroup).stream().anyMatch(text::contains);
    }

    public record DefaultPolicy(
            PoiDomainType category,
            int minDurationMinutes,
            int maxDurationMinutes,
            int defaultDurationMinutes,
            List<PoiCategoryPolicy.TimeWindow> preferredTimeWindows,
            boolean allowedAsFirstStop,
            boolean allowedAsLastStop,
            int maxOccurrencesPerDay,
            int minIntervalMinutesBetweenSameCategory
    ) implements PoiCategoryPolicy {}
}
