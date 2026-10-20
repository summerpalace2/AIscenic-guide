package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.TravelConstraints;
import java.util.List;
import java.util.Map;

/**
 * 行程组装与景点元数据查询端口接口
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 */
public interface ItineraryBuilderPort {

    Map<String, Object> build(int version, TravelConstraints constraints);

    Map<String, Object> buildLegacy(int version, TravelConstraints constraints);

    Map<String, Object> createStop(Attraction attraction, String stopId, String time,
                                  String duration, String summary, String detail);

    Map<String, Object> createDiningStop(com.ai.guide.domain.dining.model.DiningOption dining, String stopId, String time, String district);

    Attraction attraction(String id);

    List<Attraction> attractionServiceList();

    int catalogRank(String attractionId);

    /** Catalog identity check; implementations may support their explicitly configured legacy catalog. */
    default boolean belongsToCity(Attraction attraction, String city) {
        if (attraction == null || city == null || city.isBlank()) return false;
        String declared = attraction.getCity();
        if ((declared == null || declared.isBlank()) && attraction.getAmapQuery() != null)
            declared = String.valueOf(attraction.getAmapQuery().getOrDefault("city", ""));
        return normalizeCity(city).equals(normalizeCity(declared));
    }

    static String normalizeCity(String city) {
        String value = city == null ? "" : city.trim();
        return value.endsWith("市") && value.length() > 2 ? value.substring(0, value.length() - 1) : value;
    }
}

