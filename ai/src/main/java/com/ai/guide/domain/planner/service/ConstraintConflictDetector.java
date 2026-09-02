package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.ConstraintConflict;
import com.ai.guide.domain.planner.model.ConstraintOrigin;
import com.ai.guide.domain.planner.model.TravelConstraints;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 行程约束冲突智能检测服务
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 * 架构职责：在生成或调整方案时，比对多项约束规则并输出冲突诊断结果与修复建议。
 */
@Service
public class ConstraintConflictDetector {

    public List<ConstraintConflict> detectConflicts(TravelConstraints base, TravelConstraints requested) {
        List<ConstraintConflict> conflicts = new ArrayList<>();
        if (base == null || requested == null) return conflicts;

        // 1. mustVisit 与 avoid 同一景点冲突
        if (requested.getMustVisit() != null && requested.getAvoid() != null && !requested.getMustVisit().isEmpty() && !requested.getAvoid().isEmpty()) {
            Set<String> must = requested.getMustVisit().stream().map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toSet());
            for (String av : requested.getAvoid()) {
                String avoidTrimmed = av.trim();
                if (!avoidTrimmed.isBlank() && (must.contains(avoidTrimmed) || must.stream().anyMatch(m -> m.contains(avoidTrimmed) || avoidTrimmed.contains(m)))) {
                    conflicts.add(new ConstraintConflict(
                            "mustVisit_vs_avoid",
                            "HARD_MUTUAL_EXCLUSION",
                            "同一景点“" + avoidTrimmed + "”同时出现在必去与避开列表中，存在硬约束冲突。",
                            requested.getMustVisit(), requested.originOf("mustVisit"),
                            requested.getAvoid(), requested.originOf("avoid")
                    ));
                    break;
                }
            }
        }

        // 2. UI 天数 vs 文本明确天数冲突
        boolean baseHasExplicitDays = base.originOf("durationDays") == ConstraintOrigin.PROMPT
                || base.originOf("durationDays") == ConstraintOrigin.USER_TEXT;
        boolean reqHasExplicitDays = requested.originOf("durationDays") == ConstraintOrigin.REQUEST
                || requested.originOf("durationDays") == ConstraintOrigin.UI_FORM;
        if (baseHasExplicitDays && reqHasExplicitDays && base.getDurationDays() != requested.getDurationDays()) {
            conflicts.add(new ConstraintConflict(
                    "durationDays",
                    "DURATION_MISMATCH",
                    "输入文本指定了 " + base.getDurationDays() + " 天，但表单设定为 " + requested.getDurationDays() + " 天。",
                    base.getDurationDays(), base.originOf("durationDays"),
                    requested.getDurationDays(), requested.originOf("durationDays")
            ));
        }

        // 3. 到达晚于离开 (星期 / 时间顺序逆序)
        if (!"未提供".equals(requested.getArrivalAt()) && !"未提供".equals(requested.getDepartureAt())) {
            int arrDay = parseDayOfWeek(requested.getArrivalAt());
            int depDay = parseDayOfWeek(requested.getDepartureAt());
            if (arrDay > 0 && depDay > 0 && depDay < arrDay) {
                conflicts.add(new ConstraintConflict(
                        "travel_dates",
                        "DEPARTURE_BEFORE_ARRIVAL",
                        "离开时间（" + requested.getDepartureAt() + "）在星期顺序上早于抵达时间（" + requested.getArrivalAt() + "）。",
                        requested.getArrivalAt(), requested.originOf("arrivalAt"),
                        requested.getDepartureAt(), requested.originOf("departureAt")
                ));
            }
        }

        return conflicts;
    }

    private int parseDayOfWeek(String text) {
        if (text == null) return 0;
        if (text.contains("一")) return 1;
        if (text.contains("二")) return 2;
        if (text.contains("三")) return 3;
        if (text.contains("四")) return 4;
        if (text.contains("五")) return 5;
        if (text.contains("六")) return 6;
        if (text.contains("日") || text.contains("天")) return 7;
        return 0;
    }
}
