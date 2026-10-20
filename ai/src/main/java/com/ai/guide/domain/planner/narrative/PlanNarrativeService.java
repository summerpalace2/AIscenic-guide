package com.ai.guide.domain.planner.narrative;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.RouteCost;
import com.ai.guide.domain.planner.model.ScoreBreakdown;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/**
 * 行程整体综述与每日导览文本生成服务
 *
 * 所属领域：domain.planner.narrative（规划叙事与理由生成）
 */
@Service
public class PlanNarrativeService {
    private final PlannerTaxonomyPort taxonomy;

    @Autowired
    public PlanNarrativeService(PlannerTaxonomyPort taxonomy) { this.taxonomy = taxonomy == null ? TaxonomyPort.empty() : taxonomy; }
    public PlanNarrativeService() { this(TaxonomyPort.empty()); }

    public String buildStopReason(Attraction attraction,
                                  ScoreBreakdown scoreBreakdown,
                                  RouteCost routeCostFromPrevious,
                                  TravelConstraints constraints) {
        if (attraction == null) return "推荐游览。";

        List<String> evidenceParts = new ArrayList<>();

        // 1. Interest & Category Evidence
        if (constraints != null && constraints.getInterests() != null) {
            for (String interest : constraints.getInterests()) {
                if (taxonomy.getHints("plannerNatureInterestLabels").contains(interest)) {
                    if (taxonomy.getHints("plannerNatureInterestLabels").contains(attraction.getCategory())
                            || attraction.matchesAnyTagOrFeature(taxonomy.getHints("plannerNatureFeatures").toArray(String[]::new))) {
                        evidenceParts.add("符合自然山水偏好");
                        break;
                    }
                } else if (taxonomy.getHints("plannerHistoryInterestLabels").contains(interest)) {
                    if (taxonomy.getHints("plannerHistoryInterestLabels").contains(attraction.getCategory())
                            || attraction.matchesAnyTagOrFeature(taxonomy.getHints("plannerHistoryFeatures").toArray(String[]::new))) {
                        evidenceParts.add("符合历史人文偏好");
                        break;
                    }
                } else if (taxonomy.getHints("chongqingTerrainInterestLabels").contains(interest) && usesCityStrategy(constraints.getDestination(), "CHONGQING")) {
                    if (taxonomy.getHints("chongqingTerrainInterestFeatures").stream().anyMatch(attraction::matchesAnyTagOrFeature)) {
                        evidenceParts.add(taxonomy.getHints("chongqingTerrainReason").stream().findFirst().orElse(""));
                        break;
                    }
                } else if (taxonomy.getHints("plannerNightInterestLabels").contains(interest)) {
                    if (taxonomy.getHints("plannerNightCategories").contains(attraction.getCategory())
                            || attraction.matchesAnyTagOrFeature(taxonomy.getHints("plannerNightTags").toArray(String[]::new))) {
                        evidenceParts.add("契合夜景观赏需求");
                        break;
                    }
                } else if (taxonomy.getHints("plannerThermalInterestLabels").contains(interest)) {
                    if (taxonomy.getHints("plannerThermalCategories").contains(attraction.getCategory())
                            || attraction.matchesAnyTagOrFeature(taxonomy.getHints("plannerThermalFeatures").toArray(String[]::new))
                            || taxonomy.getHints("plannerThermalNameTerms").stream().anyMatch(attraction.getName()::contains)) {
                        evidenceParts.add("契合温泉康养需求");
                        break;
                    }
                } else if (taxonomy.getHints("plannerLocalInterestLabels").contains(interest)) {
                    if (taxonomy.getHints("plannerLocalCategories").contains(attraction.getCategory())
                            || attraction.matchesAnyTagOrFeature(taxonomy.getHints("localCultureTags").toArray(String[]::new))) {
                        evidenceParts.add("体现当地街区生活特色");
                        break;
                    }
                }
            }
        }

        // 2. Environment & Weather Evidence
        if ("INDOOR".equals(attraction.getEffectiveEnvironment())) {
            evidenceParts.add("室内环境舒适且雨天友好");
        } else if ("SUPPORTED".equals(attraction.getEffectiveAccessibility()) || "低".equals(attraction.getWalkDifficulty())) {
            if (constraints != null && "低".equals(constraints.getWalkingTolerance())) {
                evidenceParts.add("地势平缓或配备无障碍扶梯设施，适合低步行强度需求");
            }
        }

        // 3. Companion Fit Evidence
        if (constraints != null && constraints.getCompanions() != null) {
            String companions = constraints.getCompanions();
            if (matchesAny(companions, "plannerCompanionSeniorAliases")
                    && attraction.matchesAnyTagOrFeature(taxonomy.getHints("plannerCompanionSeniorFeatures").toArray(String[]::new))) {
                evidenceParts.add("对随行长辈友好");
            } else if (matchesAny(companions, "plannerCompanionChildAliases")
                    && attraction.matchesAnyTagOrFeature(taxonomy.getHints("plannerCompanionChildEducationFeatures").toArray(String[]::new))) {
                evidenceParts.add("具备良好亲子研学科普属性");
            } else if (matchesAny(companions, "plannerCompanionCoupleAliases")
                    && attraction.matchesAnyTagOrFeature(taxonomy.getHints("plannerCompanionCoupleRomanceFeatures").toArray(String[]::new))) {
                evidenceParts.add("景观开阔具有浪漫氛围");
            }
        }

        // 4. Adjacent Transition Route Evidence
        if (routeCostFromPrevious != null && routeCostFromPrevious.duration().toMinutes() > 0) {
            long mins = routeCostFromPrevious.duration().toMinutes();
            String statusNote = routeCostFromPrevious.status() == RouteCost.RouteDataStatus.VERIFIED_AMAP
                    ? "高德核验路线" : "路网估算";
            evidenceParts.add("距上一站预计交通约 " + mins + " 分钟（" + statusNote + "）");
        }

        if (evidenceParts.isEmpty()) {
            return "安排“" + attraction.getName() + "”，" + attraction.getSummary();
        }

        return "安排“" + attraction.getName() + "”是因为它" + String.join("，", evidenceParts) + "。";
    }

    private boolean usesCityStrategy(String city, String strategy) {
        if (city == null || city.isBlank()) return false;
        return taxonomy.getCityStrategyMappings().entrySet().stream()
                .anyMatch(entry -> entry.getValue().equalsIgnoreCase(strategy)
                        && entry.getKey().replace("市", "").equalsIgnoreCase(city.trim().replace("市", "")));
    }

    private boolean matchesAny(String value, String taxonomyKey) {
        return value != null && taxonomy.getHints(taxonomyKey).stream().anyMatch(alias ->
                value.equalsIgnoreCase(alias) || value.contains(alias));
    }
}
