package com.ai.guide.domain.planner.narrative;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.RouteCost;
import com.ai.guide.domain.planner.model.ScoreBreakdown;
import com.ai.guide.domain.planner.model.TravelConstraints;
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

    public String buildStopReason(Attraction attraction,
                                  ScoreBreakdown scoreBreakdown,
                                  RouteCost routeCostFromPrevious,
                                  TravelConstraints constraints) {
        if (attraction == null) return "推荐游览。";

        List<String> evidenceParts = new ArrayList<>();

        // 1. Interest & Category Evidence
        if (constraints != null && constraints.getInterests() != null) {
            for (String interest : constraints.getInterests()) {
                if ("自然".equals(interest) || "自然奇观".equals(interest)) {
                    if ("自然".equals(attraction.getCategory()) || attraction.matchesAnyTagOrFeature("自然", "峡谷", "喀斯特峡谷")) {
                        evidenceParts.add("符合自然山水偏好");
                        break;
                    }
                } else if ("人文".equals(interest) || "人文历史".equals(interest)) {
                    if ("人文".equals(attraction.getCategory()) || attraction.matchesAnyTagOrFeature("人文", "历史", "古建", "抗战遗址", "千年古刹")) {
                        evidenceParts.add("符合历史人文偏好");
                        break;
                    }
                } else if ("8D魔幻".equals(interest)) {
                    if ("8D魔幻".equals(attraction.getCategory()) || attraction.matchesAnyTagOrFeature("8D魔幻", "单轨", "天桥", "空中连廊")) {
                        evidenceParts.add("具备8D魔幻地形特色");
                        break;
                    }
                } else if ("夜景".equals(interest) || "山城夜景".equals(interest)) {
                    if ("夜景".equals(attraction.getCategory()) || attraction.matchesAnyTagOrFeature("夜景", "临江", "江岸夜景")) {
                        evidenceParts.add("契合山城夜景需求");
                        break;
                    }
                } else if ("温泉".equals(interest) || "天然温泉".equals(interest)) {
                    if ("温泉".equals(attraction.getCategory()) || attraction.matchesAnyTagOrFeature("温泉", "天然地热温泉") || attraction.getName().contains("温泉")) {
                        evidenceParts.add("契合温泉康养需求");
                        break;
                    }
                } else if ("市井".equals(interest) || "市井烟火".equals(interest)) {
                    if ("市井".equals(attraction.getCategory()) || attraction.matchesAnyTagOrFeature("市井", "市井烟火", "老街", "老重庆")) {
                        evidenceParts.add("展现巴渝市井烟火气息");
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
            if ("带父母".equals(companions) && attraction.matchesAnyTagOrFeature("长辈最爱", "长辈友好", "少走路")) {
                evidenceParts.add("对随行长辈友好");
            } else if ("带孩子".equals(companions) && attraction.matchesAnyTagOrFeature("亲子", "科普", "国宝级")) {
                evidenceParts.add("具备良好亲子研学科普属性");
            } else if ("情侣出游".equals(companions) && attraction.matchesAnyTagOrFeature("浪漫", "夜景", "开埠文化")) {
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
}
