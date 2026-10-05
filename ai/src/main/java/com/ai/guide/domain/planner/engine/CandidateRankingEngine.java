package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.TravelConstraints;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 候选景点与调整站点时空距离加权打分引擎
 */
@Component
public class CandidateRankingEngine {

    public record CandidateContext(Attraction oldAttr, Attraction prevAttr, Attraction nextAttr, String startCoordinate) {
        public static CandidateContext of(Attraction oldAttr) {
            return new CandidateContext(oldAttr, null, null, null);
        }
    }

    public record ScoredCandidate(Attraction attraction, int score) {}

    private final RouteCostProvider routeCostProvider;

    public CandidateRankingEngine(RouteCostProvider routeCostProvider) {
        this.routeCostProvider = routeCostProvider;
    }

    public int scoreCandidate(Attraction candidate, Attraction old, TravelConstraints constraints, PlanAdjustmentIntent intent) {
        return scoreCandidate(candidate, CandidateContext.of(old), constraints, intent);
    }

    public int scoreCandidate(Attraction candidate, CandidateContext context, TravelConstraints constraints, PlanAdjustmentIntent intent) {
        int score = 0;
        Attraction old = context == null ? null : context.oldAttr();
        boolean preferSameDistrict = (intent != null && intent.getPreferences() != null && intent.getPreferences().contains("SAME_DISTRICT"))
                || (intent != null && intent.getRawMessage() != null && (intent.getRawMessage().contains("同片区") || intent.getRawMessage().contains("同区")));

        if (old != null && safe(candidate.getDistrict()).equals(old.getDistrict())) {
            score += preferSameDistrict ? 100 : 30;
        } else if (preferSameDistrict) {
            score -= 100;
        }

        if (constraints != null && "低".equals(constraints.getWalkingTolerance()) && "低".equals(candidate.getWalkDifficulty())) score += 25;
        if (old != null && safe(candidate.getCategory()).equals(old.getCategory())) score += 15;
        if (constraints != null && "带父母".equals(constraints.getCompanions()) && candidate.matchesAnyTagOrFeature("长辈最爱", "长辈友好", "少走路")) score += 20;
        if (constraints != null && "带孩子".equals(constraints.getCompanions()) && candidate.matchesAnyTagOrFeature("亲子", "科普")) score += 20;

        if (intent != null && intent.getPreferences() != null) {
            for (String p : intent.getPreferences()) {
                if ("INDOOR".equalsIgnoreCase(p)) {
                    if (Boolean.TRUE.equals(candidate.getIndoor()) || "INDOOR".equals(candidate.getEffectiveEnvironment())) {
                        score += 70;
                    } else if (!"MIXED".equals(candidate.getEffectiveEnvironment())) {
                        score -= 80;
                    }
                }
                if ("LOW_WALKING".equalsIgnoreCase(p) && "低".equals(candidate.getWalkDifficulty())) score += 30;
                if ("HISTORY".equalsIgnoreCase(p) && ("人文".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("历史", "古建", "文物", "古镇"))) score += 30;
                if ("NIGHT_VIEW".equalsIgnoreCase(p) && ("夜景".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("夜景", "临江"))) score += 30;
                if ("FOOD".equalsIgnoreCase(p) && ("美食".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("美食", "老字号"))) score += 30;
                if ("NATURE".equalsIgnoreCase(p) && ("自然".equals(candidate.getCategory()) || candidate.matchesAnyTagOrFeature("自然", "峡谷", "喀斯特"))) score += 30;
            }
        }

        // 空间几何距离打分与超距惩罚
        double distKm = calculateDistanceToContext(candidate, context);
        if (distKm <= 3.5) {
            score += 45; // 紧凑邻近加分
        } else if (distKm <= 6.0) {
            score += 20; // 局部辐射圈加分
        } else if (distKm > 6.0) {
            score -= (int) Math.round((distKm - 6.0) * 12); // 超出 6km 阶梯扣分
        }

        // 限时规划（如 <= 360分钟 / 6小时）：出行时间极为紧缺，对远距离施加强力惩罚
        boolean isShortTimeBudget = constraints != null && constraints.getTimeBudgetMinutes() > 0
                && constraints.getTimeBudgetMinutes() <= 360;
        if (isShortTimeBudget) {
            if (distKm > 8.0) {
                score -= 80;
            }
            if (distKm > 12.0) {
                score -= 200; // 严厉淘汰，坚决防止短途规划被拉扯到对角线城区
            }
        }

        return score;
    }

    public double calculateDistanceToContext(Attraction candidate, CandidateContext context) {
        if (candidate == null || candidate.getLocation() == null || !candidate.getLocation().contains(",")) {
            return 8.0;
        }
        String candLoc = candidate.getLocation();
        List<Double> distances = new ArrayList<>();

        if (context != null) {
            if (context.oldAttr() != null && context.oldAttr().getLocation() != null && context.oldAttr().getLocation().contains(",")) {
                distances.add(routeCostProvider.haversineDistanceKm(candLoc, context.oldAttr().getLocation()));
            }
            if (context.prevAttr() != null && context.prevAttr().getLocation() != null && context.prevAttr().getLocation().contains(",")) {
                distances.add(routeCostProvider.haversineDistanceKm(candLoc, context.prevAttr().getLocation()));
            } else if (context.startCoordinate() != null && context.startCoordinate().contains(",")) {
                distances.add(routeCostProvider.haversineDistanceKm(candLoc, context.startCoordinate()));
            }
            if (context.nextAttr() != null && context.nextAttr().getLocation() != null && context.nextAttr().getLocation().contains(",")) {
                distances.add(routeCostProvider.haversineDistanceKm(candLoc, context.nextAttr().getLocation()));
            }
        }

        if (distances.isEmpty()) {
            return 5.0;
        }
        double sum = 0;
        double min = Double.MAX_VALUE;
        for (double d : distances) {
            sum += d;
            if (d < min) min = d;
        }
        double avg = sum / distances.size();
        return avg * 0.6 + min * 0.4;
    }

    public boolean isAvoided(Attraction attraction, TravelConstraints constraints) {
        if (constraints == null || constraints.getAvoid() == null) return false;
        return constraints.getAvoid().stream().anyMatch(avoid ->
                attraction.getName().contains(avoid)
                        || attraction.getId().contains(avoid)
                        || avoid.equals(attraction.getCategory()));
    }

    public boolean isCrossDistrictInviable(Attraction left, Attraction right) {
        if (left == null || right == null) return false;
        boolean leftDistant = isDistantDistrict(left.getDistrict());
        boolean rightDistant = isDistantDistrict(right.getDistrict());
        return leftDistant != rightDistant;
    }

    public boolean isDistantDistrict(String district) {
        if (district == null) return false;
        return district.contains("涪陵") || district.contains("武隆") || district.contains("大足");
    }

    public boolean isOpeningCompatible(Attraction attraction, String slotTime, ItineraryTimeEngine timeEngine) {
        if (attraction == null || slotTime == null || slotTime.isBlank()) return true;
        int slotMinutes = timeEngine == null ? 720 : timeEngine.parseTimeMinutes(slotTime);
        if (slotMinutes <= 0) return true;

        // 跨午夜拦截：当天排程大于等于 24:00 (1440 分钟) 或携带跨天标记视为不可行
        if (slotMinutes >= 24 * 60 || slotTime.contains("(+")) {
            return false;
        }

        // 索道与高空观光设施官方营业时间门禁：通常 08:00 - 22:00（21:30 停止检票/售票）
        String attrName = safe(attraction.getName());
        String attrCat = safe(attraction.getCategory());
        boolean isCableway = attrName.contains("索道") || attrCat.contains("索道")
                || (attraction.getTags() != null && attraction.getTags().contains("索道"));
        if (isCableway) {
            if (slotMinutes < 8 * 60 || slotMinutes >= 22 * 60) {
                return false;
            }
        }

        // 博物馆/陈列馆/科技馆类通常 17:00 闭馆
        String bestTime = safe(attraction.getBestTime());
        String category = safe(attraction.getCategory());
        String name = safe(attraction.getName());
        if (bestTime.contains("17:00") || category.contains("博物馆") || name.contains("博物馆") || name.contains("陈列馆")) {
            if (slotMinutes >= 17 * 60) {
                return false;
            }
        }

        boolean isEve = timeEngine != null && timeEngine.isEveningSlot(slotTime);
        return !(bestTime.contains("17:00") && isEve);
    }

    private String safe(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}
