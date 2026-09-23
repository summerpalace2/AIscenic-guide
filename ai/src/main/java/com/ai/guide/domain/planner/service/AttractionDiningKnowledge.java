package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.model.TravelConstraints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 景区周边地道美食与早中晚用餐推荐门面适配器
 *
 * 架构说明：
 * 原类中硬编码的 40 余家餐厅池已完全迁移至结构化数据库表 (dining_venue) 与 resources/dining_venues.json 种子中。
 * 本适配器保留原始静态调用签名以维护向前兼容，底层统一委托给 DiningVenueService 完成本地检索与高德 LBS 兜底。
 */
@Component
public final class AttractionDiningKnowledge {

    public record DiningOption(
            String id,
            String name,
            String specialtyDish,
            String diningType,
            String averageCost,
            String duration,
            String distanceFromAttraction,
            String summary,
            String recommendationReason,
            String tone,
            String location
    ) {}

    private static volatile DiningVenueService delegate = new DiningVenueService();

    @Autowired
    public void setDiningVenueService(DiningVenueService diningVenueService) {
        if (diningVenueService != null) {
            delegate = diningVenueService;
        }
    }

    public static void setStaticDelegate(DiningVenueService diningVenueService) {
        if (diningVenueService != null) {
            delegate = diningVenueService;
        }
    }

    private AttractionDiningKnowledge() {}

    /**
     * 根据邻近景区实体与用户偏好，匹配最佳周边餐饮推荐。
     */
    public static DiningOption resolveNearbyDining(Attraction anchorAttraction, String slotType, TravelConstraints constraints) {
        return delegate.resolveNearbyDining(anchorAttraction, slotType, constraints);
    }

    public static DiningOption resolveNearbyDining(Attraction anchorAttraction, String slotType, TravelConstraints constraints, java.util.Set<String> excludedNames) {
        return delegate.resolveNearbyDining(anchorAttraction, slotType, constraints, excludedNames);
    }

    /**
     * 为餐饮节点生成局部替换候选列表（覆盖火锅、小面/早点、江湖菜、清淡汤锅/小吃、休闲茶歇等）
     */
    public static List<DiningOption> resolveDiningReplacementCandidates(
            String district,
            String currentDiningName,
            String requestedFlavor,
            String slotType,
            Attraction anchorAttraction
    ) {
        return delegate.resolveDiningReplacementCandidates(
                district, currentDiningName, requestedFlavor, slotType, anchorAttraction, null);
    }

    public static List<DiningOption> resolveDiningReplacementCandidates(
            String district,
            String currentDiningName,
            String requestedFlavor,
            String slotType,
            Attraction anchorAttraction,
            TravelConstraints constraints
    ) {
        return delegate.resolveDiningReplacementCandidates(
                district, currentDiningName, requestedFlavor, slotType, anchorAttraction, constraints);
    }
}
