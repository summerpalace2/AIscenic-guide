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

    Attraction attraction(String id);

    List<Attraction> attractionServiceList();

    int catalogRank(String attractionId);
}
