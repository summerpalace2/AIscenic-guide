package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.planner.service.PlannerMetricsLogger;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.ScoreBreakdown;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.ai.guide.domain.planner.service.TravelDateResolver;
import com.ai.guide.domain.planner.service.ChongqingTopographyKnowledge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 路线感知启发式多天行程排程引擎
 *
 * 所属领域：domain.planner.engine（规划引擎核心算法层）
 * 架构职责：综合考量景点开放与闭园时段、建议游玩耗时、站点间交通距离与通行时间（高德 RouteCost）、出入渝时限及地理聚类特征，执行贪心聚类排程与约束打分。
 *
 * 核心方法与职责：
 * 1. plan：基于用户偏好与候选景点池，计算并生成满足约束的最优多天排程方案
 * 2. scorePlan：对生成方案进行多维度评分（时间充裕度、偏好契合度、交通紧凑度、路线顺畅度）
 */
@Service
public class RouteAwarePlanner {

    private final RouteCostProvider routeCostProvider;
    private final PlanVerifier planVerifier;
    private final LocalPlanRepairer localPlanRepairer;
    private final PlannerMetricsLogger metricsLogger;

    @Autowired
    public RouteAwarePlanner(RouteCostProvider routeCostProvider,
                             PlanVerifier planVerifier,
                             LocalPlanRepairer localPlanRepairer,
                             @Autowired(required = false) PlannerMetricsLogger metricsLogger) {
        this.routeCostProvider = routeCostProvider;
        this.planVerifier = planVerifier;
        this.localPlanRepairer = localPlanRepairer;
        this.metricsLogger = metricsLogger != null ? metricsLogger : new PlannerMetricsLogger();
    }

    public RouteAwarePlanner(RouteCostProvider routeCostProvider,
                             PlanVerifier planVerifier,
                             LocalPlanRepairer localPlanRepairer) {
        this(routeCostProvider, planVerifier, localPlanRepairer, new PlannerMetricsLogger());
    }

    public record CandidateEvaluation(
            Attraction attraction,
            ScoreBreakdown scoreBreakdown,
            RouteCost routeCostFromPrevious,
            double utility
    ) {}

    public List<Map<String, Object>> planDays(int dayCount,
                                              TravelConstraints constraints,
                                              Map<String, Attraction> byId,
                                              List<String> explicitIds,
                                              ItineraryBuilderPort builder) {
        List<Map<String, Object>> days = new ArrayList<>();
        Set<String> usedAttractionIds = new HashSet<>();
        String[] themes = {"母城地标与两江夜景", "文博探索与山城魔幻", "江岸开阔视野与工业文创", "千年古镇与巴渝风情"};

        for (int day = 1; day <= dayCount; day++) {
            String dayDate = calculateDayDate(constraints, day);
            boolean isMonday = dayDate != null && (dayDate.contains("一") || isDateMonday(dayDate));
            List<SlotSpecification> slots = buildDaySlots(day, dayCount, constraints);
            List<Map<String, Object>> stops = new ArrayList<>();
            List<Attraction> currentDayAttractions = new ArrayList<>();
            Attraction previousAttraction = null;

            for (SlotSpecification slot : slots) {
                CandidateEvaluation best = selectBestCandidate(
                        byId.values(),
                        usedAttractionIds,
                        currentDayAttractions,
                        previousAttraction,
                        slot,
                        isMonday,
                        constraints,
                        explicitIds,
                        day
                );

                if (best != null) {
                    Attraction selected = best.attraction();
                    usedAttractionIds.add(selected.getId());
                    currentDayAttractions.add(selected);
                    String slug = selected.getId().replaceFirst("^cq-", "");
                    String stopId = "day" + day + "-" + slug;

                    Map<String, Object> stop = builder.createStop(
                            selected,
                            stopId,
                            slot.time(),
                            selected.getDuration(),
                            selected.getSummary(),
                            selected.getIntro()
                    );
                    if (best.scoreBreakdown() != null) {
                        stop.put("scoreBreakdown", best.scoreBreakdown().asMap());
                    }
                    if (best.routeCostFromPrevious() != null) {
                        stop.put("estimatedRouteCost", best.routeCostFromPrevious().asMap());
                        stop.put("resolvedRouteCost", best.routeCostFromPrevious().asMap());
                        stop.put("routeDataStatus", best.routeCostFromPrevious().status().name());
                    }
                    if (previousAttraction != null) {
                        String topographyTransitHint = ChongqingTopographyKnowledge.generateTransitCharacteristicHint(
                                previousAttraction, selected, constraints.getTransportPreference());
                        if (topographyTransitHint != null && !topographyTransitHint.isBlank()) {
                            stop.put("topographyTransitHint", topographyTransitHint);
                            stop.put("trafficHint", topographyTransitHint);
                        }
                    }
                    stops.add(stop);
                    previousAttraction = selected;
                }
            }

            Map<String, Object> dayMap = new LinkedHashMap<>();
            dayMap.put("day", day);
            dayMap.put("date", dayDate == null ? dateLabel(constraints, day) : dayDate);
            dayMap.put("dateLabel", dayDate == null
                    ? "第" + day + "天 · " + themes[(day - 1) % themes.length]
                    : dayDate + " · 第" + day + "天 · " + themes[(day - 1) % themes.length]);
            dayMap.put("weather", Map.of("label", "天气", "value", "未知·待查询", "status", "未知", "note", "出发前需确认动态天气。"));
            dayMap.put("stops", stops);
            days.add(dayMap);
        }

        // Closed feedback loop: query live route on draft adjacent edges -> verify -> repair (max 2 rounds)
        return resolveAndVerifyDays(days, byId, constraints);
    }

    public List<Map<String, Object>> resolveAndVerifyDays(List<Map<String, Object>> days,
                                                         Map<String, Attraction> byId,
                                                         TravelConstraints constraints) {
        if (days == null || days.isEmpty()) return days == null ? new ArrayList<>() : days;
        List<Attraction> catalogList = new ArrayList<>(byId.values());
        List<Map<String, Object>> currentDays = days;
        PlanVerifier.VerificationResult verification = null;
        String preference = constraints == null ? "walking" : constraints.getTransportPreference();

        for (int round = 0; round <= 2; round++) {
            // 1. Resolve and backfill RouteCost for each adjacent edge in the current draft days
            backfillAdjacentEdgeCosts(currentDays, byId, preference);

            // 2. Verify with resolved route timing and constraints
            verification = planVerifier.verify(currentDays, constraints);

            // 3. If feasible or reached max repair rounds (round == 2), stop loop
            if (verification.feasible() || round == 2) {
                break;
            }

            // 4. Infeasible: attempt local repair
            if (metricsLogger != null && verification.violations() != null) {
                for (PlanVerifier.PlanViolation v : verification.violations()) {
                    if (v.isHardConstraint()) {
                        metricsLogger.logPlanRepaired("planner-session", v.dayNumber(), v.type(), round + 1);
                    }
                }
            }
            currentDays = localPlanRepairer.repair(currentDays, verification, constraints, catalogList);
        }

        return currentDays;
    }

    public void backfillAdjacentEdgeCosts(List<Map<String, Object>> days,
                                         Map<String, Attraction> byId,
                                         String preference) {
        if (days == null) return;
        for (Map<String, Object> day : days) {
            Object stopsObj = day.get("stops");
            if (!(stopsObj instanceof List<?> list)) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) list;
            for (int i = 1; i < stops.size(); i++) {
                Map<String, Object> prevStop = stops.get(i - 1);
                Map<String, Object> currStop = stops.get(i);
                String prevVenueId = String.valueOf(prevStop.get("venueId"));
                String currVenueId = String.valueOf(currStop.get("venueId"));
                Attraction prevAttraction = byId.get(prevVenueId);
                Attraction currAttraction = byId.get(currVenueId);

                if (prevAttraction != null && currAttraction != null) {
                    RouteCost resolved = routeCostProvider.resolveFinalEdge(prevAttraction, currAttraction, preference);
                    currStop.put("resolvedRouteCost", resolved.asMap());
                    currStop.put("estimatedRouteCost", resolved.asMap());
                    currStop.put("routeDataStatus", resolved.status().name());
                }
            }
        }
    }

    private CandidateEvaluation selectBestCandidate(Collection<Attraction> catalog,
                                                    Set<String> used,
                                                    List<Attraction> currentDayAttractions,
                                                    Attraction previous,
                                                    SlotSpecification slot,
                                                    boolean isMonday,
                                                    TravelConstraints constraints,
                                                    List<String> explicitIds,
                                                    int day) {
        CandidateEvaluation best = null;
        double maxUtility = -Double.MAX_VALUE;

        for (Attraction candidate : catalog) {
            if (used.contains(candidate.getId())) continue;

            // Hard constraint 0: Distant district requires explicit mention or matching start place
            boolean isCandidateDistant = isDistantDistrict(candidate.getDistrict());
            if (isCandidateDistant) {
                boolean explicitlyRequested = (explicitIds != null && explicitIds.contains(candidate.getId()))
                        || (constraints.getMustVisit() != null && constraints.getMustVisit().stream().anyMatch(m -> candidate.getName().contains(m) || candidate.getId().contains(m)));
                String sp = constraints.getStartPlace() == null ? "" : constraints.getStartPlace();
                boolean matchesStartPlace = isDistrictMatchingStartPlace(sp, candidate.getDistrict());
                if (!explicitlyRequested && !matchesStartPlace) {
                    continue;
                }
            }

            // Hard constraint 1: Avoid list
            if (constraints.getAvoid() != null && constraints.getAvoid().stream().anyMatch(av ->
                    candidate.getName().contains(av) || candidate.getId().contains(av) || av.equals(candidate.getCategory()))) {
                continue;
            }

            // Hard constraint 2: Opening Hours - Monday closure (GS-03)
            String bestTime = candidate.getBestTime() == null ? "" : candidate.getBestTime();
            if (isMonday && (bestTime.contains("周一闭馆") || bestTime.contains("周一休息"))) {
                continue;
            }

            // Hard constraint 3: Slot time feasibility (GS-03)
            if (bestTime.contains("17:00") && isEveningSlot(slot.time())) {
                continue; // Cannot schedule 17:00 closing venue at 18:30/19:30 evening
            }
            if (isNightAttraction(candidate) && !isEveningSlot(slot.time())) {
                continue; // Cannot schedule night attraction at morning/afternoon slot
            }

            // Hard constraint 4: Cross-district half-day collision (GS-01)
            if (previous != null) {
                boolean prevDistant = isDistantDistrict(previous.getDistrict());
                boolean currDistant = isDistantDistrict(candidate.getDistrict());
                if (prevDistant != currDistant) {
                    continue; // Do not bounce between Fuling/Wulong and Yuzhong downtown in adjacent slots
                }
            }

            // Soft score & Route calculation (Estimates only in candidate phase)
            ScoreBreakdown breakdown = calculateAttractionScoreBreakdown(candidate, constraints, explicitIds, previous, day);
            RouteCost routeCost = previous == null
                    ? RouteCost.estimated(0, 0, 0, "WALKING", "首站")
                    : routeCostProvider.calculate(previous, candidate, constraints.getTransportPreference());

            double travelPenalty = routeCost.duration().toMinutes() * 0.4;
            double walkingPenalty = "低".equals(constraints.getWalkingTolerance()) ? (routeCost.walkingMeters() * 0.03) : 0;
            double timePreferenceBonus = slot.preferredPredicate().test(candidate) ? 25.0 : 0.0;

            // 1. 折返惩罚（Zig-zag Penalty）：杜绝同一天在“山顶 ↔ 滨江”反复横跳或频繁来回跨江
            double zigZagPenalty = ChongqingTopographyKnowledge.calculateZigZagPenalty(currentDayAttractions, candidate);

            // 2. 顺势而下加分与爬坡减分（Downhill First for Low-Walking / Elder-Friendly）
            double topographyBonus = 0.0;
            if (previous != null) {
                boolean isLowWalking = "低".equals(constraints.getWalkingTolerance())
                        || (constraints.getCompanions() != null && constraints.getCompanions().contains("父母"))
                        || (constraints.getRawPrompt() != null && (constraints.getRawPrompt().contains("少走")
                                || constraints.getRawPrompt().contains("轻松") || constraints.getRawPrompt().contains("坡")));
                ChongqingTopographyKnowledge.ElevationFlow flow = ChongqingTopographyKnowledge.detectElevationFlow(previous, candidate);
                if (isLowWalking) {
                    if (flow == ChongqingTopographyKnowledge.ElevationFlow.DOWNHILL) {
                        topographyBonus += 25.0; // 顺山势下坡，长辈游极度舒适
                    } else if (flow == ChongqingTopographyKnowledge.ElevationFlow.UPHILL) {
                        topographyBonus -= 30.0; // 逆山势爬坡，长辈体力消耗过大
                    }
                }
            }

            double utility = breakdown.totalScore() - travelPenalty - walkingPenalty + timePreferenceBonus - zigZagPenalty + topographyBonus;

            if (utility > maxUtility) {
                maxUtility = utility;
                best = new CandidateEvaluation(candidate, breakdown, routeCost, utility);
            }
        }

        return best;
    }

    private ScoreBreakdown calculateAttractionScoreBreakdown(Attraction a, TravelConstraints c, List<String> explicitIds, Attraction previous, int day) {
        List<String> reasonCodes = new ArrayList<>();
        int explicitBonus = explicitIds != null && explicitIds.contains(a.getId()) ? 200 : 0;
        int mustVisitScore = (c.getMustVisit() != null && c.getMustVisit().stream().anyMatch(m -> a.getName().contains(m) || a.getId().contains(m))) ? 300 : 0;
        int avoidPenalty = (c.getAvoid() != null && c.getAvoid().stream().anyMatch(av -> a.getName().contains(av) || a.getId().contains(av) || av.equals(a.getCategory()))) ? -500 : 0;

        int interestScore = 0;
        for (String interest : c.getInterests()) {
            if ("自然".equals(interest) || "自然奇观".equals(interest)) {
                if ("自然".equals(a.getCategory()) || a.matchesAnyTagOrFeature("自然", "峡谷", "喀斯特峡谷")) {
                    interestScore += 55;
                    reasonCodes.add("INTEREST_NATURE");
                }
            } else if ("人文".equals(interest) || "人文历史".equals(interest)) {
                if ("人文".equals(a.getCategory()) || a.matchesAnyTagOrFeature("人文", "历史", "古建", "抗战遗址", "千年古刹", "世界文化遗产")) {
                    interestScore += 45;
                    reasonCodes.add("INTEREST_HISTORY");
                }
            } else if ("8D魔幻".equals(interest)) {
                if ("8D魔幻".equals(a.getCategory()) || a.matchesAnyTagOrFeature("8D魔幻", "单轨", "天桥", "空中连廊", "空中巴士")) {
                    interestScore += 55;
                    reasonCodes.add("INTEREST_8D_MAGIC");
                }
            } else if ("夜景".equals(interest) || "山城夜景".equals(interest)) {
                if ("夜景".equals(a.getCategory()) || a.matchesAnyTagOrFeature("夜景", "临江", "江岸夜景", "俯瞰全城")) {
                    interestScore += 45;
                    reasonCodes.add("INTEREST_NIGHT_VIEW");
                }
            } else if ("温泉".equals(interest) || "天然温泉".equals(interest)) {
                if ("温泉".equals(a.getCategory()) || a.matchesAnyTagOrFeature("温泉", "天然地热温泉", "古温泉", "养生放松") || a.getName().contains("温泉")) {
                    interestScore += 60;
                    reasonCodes.add("INTEREST_HOTSPRING");
                }
            } else if ("市井".equals(interest) || "市井烟火".equals(interest)) {
                if ("市井".equals(a.getCategory()) || a.matchesAnyTagOrFeature("市井", "市井烟火", "老街", "古镇", "老重庆")) {
                    interestScore += 45;
                    reasonCodes.add("INTEREST_LOCAL_LIFE");
                }
            } else if (a.matchesAnyTagOrFeature(interest) || interest.equals(a.getCategory()) || a.getName().contains(interest)) {
                interestScore += 40;
                reasonCodes.add("INTEREST_TAG_MATCH");
            }
        }

        int walkingScore = 0;
        if ("低".equals(c.getWalkingTolerance())) {
            if ("低".equals(a.getWalkDifficulty()) || "SUPPORTED".equals(a.getEffectiveAccessibility())) walkingScore += 35;
            if (a.matchesAnyTagOrFeature("少走路", "长辈友好", "平街", "无障碍")) walkingScore += 25;
            if ("高".equals(a.getWalkDifficulty())) walkingScore -= 50;
            if ("中".equals(a.getWalkDifficulty())) walkingScore -= 15;
        }

        int companionScore = switch (c.getCompanions()) {
            case "带父母", "带老人", "长辈同行" -> {
                int delta = "INDOOR".equals(a.getEffectiveEnvironment()) ? 25 : 0;
                if (a.matchesAnyTagOrFeature("长辈最爱", "长辈友好", "无障碍", "少走路")) delta += 35;
                if ("低".equals(a.getWalkDifficulty())) delta += 30;
                if ("高".equals(a.getWalkDifficulty())) delta -= 40;
                yield delta;
            }
            case "带孩子", "亲子家庭", "亲子" -> {
                int delta = "INDOOR".equals(a.getEffectiveEnvironment()) ? 25 : 0;
                if (a.matchesAnyTagOrFeature("亲子", "科普", "国宝级")) delta += 45;
                if ("自然".equals(a.getCategory())) delta += 35;
                if (a.matchesAnyTagOrFeature("8D魔幻", "单轨")) delta += 30;
                yield delta;
            }
            case "情侣出游", "情侣双人", "情侣" -> {
                int delta = 0;
                if ("夜景".equals(a.getCategory()) || a.matchesAnyTagOrFeature("夜景", "浪漫")) delta += 35;
                if (a.matchesAnyTagOrFeature("温泉", "天然地热温泉") || a.getName().contains("温泉")) delta += 45;
                if (a.matchesAnyTagOrFeature("俯瞰全城", "开埠文化")) delta += 30;
                yield delta;
            }
            case "朋友出游", "朋友结伴" -> (a.matchesAnyTagOrFeature("8D魔幻", "美食", "打卡", "夜生活") || "城市".equals(a.getCategory())) ? 35 : 0;
            case "独自出发", "一个人", "自由行" -> (a.matchesAnyTagOrFeature("地标", "文创", "城市书房") || "文创".equals(a.getCategory())) ? 30 : 0;
            default -> 0;
        };

        int diningScore = 0;
        if ("清淡不辣".equals(c.getDietPreference()) || "清淡".equals(c.getDietPreference())) {
            if ("INDOOR".equals(a.getEffectiveEnvironment()) || a.matchesAnyTagOrFeature("最美林荫道", "清淡友好")) diningScore += 30;
            if (a.matchesAnyTagOrFeature("火锅", "江湖菜")) diningScore -= 20;
        } else if ("重庆火锅".equals(c.getDietPreference())) {
            if ("美食".equals(a.getCategory()) || a.matchesAnyTagOrFeature("火锅", "美食", "小吃")) diningScore += 40;
        }
        int districtScore = 0;
        // 起点邻近加分（START_PLACE_DISTRICT_PROXIMITY）仅在第1天起步阶段生效；
        // 当后续已有行程且面临跨江时，严禁因初始起点区位加分而强制跨江拉扯
        boolean allowStartPlaceProximity = (day == 1)
                && (previous == null || ChongqingTopographyKnowledge.detectRiverCrossing(previous, a) == ChongqingTopographyKnowledge.RiverCrossingType.NONE);
        if (allowStartPlaceProximity && c.getStartPlace() != null && !c.getStartPlace().isBlank() && !"未提供".equals(c.getStartPlace())) {
            String sp = c.getStartPlace();
            if ((sp.contains("江津") || sp.contains("白沙") || sp.contains("四面山")) && "江津区".equals(a.getDistrict())) {
                districtScore += 120;
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            } else if ((sp.contains("南岸") || sp.contains("重邮") || sp.contains("邮电") || sp.contains("南山")) && "南岸区".equals(a.getDistrict())) {
                districtScore += 80;
                if (a.getName().contains("南山") || a.getId().contains("nanshan") || a.getName().contains("龙门浩") || a.getName().contains("马鞍山")) {
                    districtScore += 40;
                }
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            } else if ((sp.contains("沙坪坝") || sp.contains("重大") || sp.contains("重庆大学") || sp.contains("大学城")) && "沙坪坝区".equals(a.getDistrict())) {
                districtScore += 80;
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            } else if ((sp.contains("渝中") || sp.contains("解放碑") || sp.contains("朝天门")) && "渝中区".equals(a.getDistrict())) {
                districtScore += 60;
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            } else if ((sp.contains("江北") || sp.contains("观音桥")) && "江北区".equals(a.getDistrict())) {
                districtScore += 60;
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            } else if ((sp.contains("九龙坡") || sp.contains("杨家坪")) && "九龙坡区".equals(a.getDistrict())) {
                districtScore += 80;
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            } else if ((sp.contains("巴南")) && "巴南区".equals(a.getDistrict())) {
                districtScore += 80;
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            } else if ((sp.contains("北碚")) && "北碚区".equals(a.getDistrict())) {
                districtScore += 80;
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            } else if ((sp.contains("大渡口")) && "大渡口区".equals(a.getDistrict())) {
                districtScore += 80;
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            } else if ((sp.contains("渝北")) && "渝北区".equals(a.getDistrict())) {
                districtScore += 60;
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            }
        }

        int totalScore = explicitBonus + mustVisitScore + avoidPenalty + interestScore + walkingScore + companionScore + diningScore + districtScore;
        return new ScoreBreakdown(interestScore, companionScore, walkingScore, diningScore, mustVisitScore, avoidPenalty, explicitBonus, totalScore, reasonCodes);
    }

    private record SlotSpecification(String time, Predicate<Attraction> preferredPredicate) {}

    private List<SlotSpecification> buildDaySlots(int day, int dayCount, TravelConstraints constraints) {
        boolean isFirstDay = (day == 1);
        boolean isLastDay = (day == dayCount);
        String arrival = constraints == null ? "" : constraints.getArrivalAt();
        String departure = constraints == null ? "" : constraints.getDepartureAt();

        // Day 1 Arrival window constraint (GS-04)
        if (isFirstDay && (arrival.contains("晚上") || arrival.contains("夜间"))) {
            return List.of(new SlotSpecification("19:30", this::isNightAttraction));
        }
        if (isFirstDay && (arrival.contains("下午"))) {
            return List.of(
                    new SlotSpecification("14:30", attraction -> !isNightAttraction(attraction)),
                    new SlotSpecification("19:30", this::isNightAttraction)
            );
        }

        // Final Day Departure window constraint
        if (isLastDay && (departure.contains("上午") || departure.contains("早晨"))) {
            return List.of(new SlotSpecification("10:00", attraction -> !isNightAttraction(attraction)));
        }
        if (isLastDay && (departure.contains("中午") || departure.contains("下午"))) {
            return List.of(
                    new SlotSpecification("10:00", attraction -> !isNightAttraction(attraction)),
                    new SlotSpecification("14:30", attraction -> !isNightAttraction(attraction))
            );
        }

        // Standard 3-slot day
        return List.of(
                new SlotSpecification("10:00", attraction -> !isNightAttraction(attraction)),
                new SlotSpecification("14:30", attraction -> !isNightAttraction(attraction)),
                new SlotSpecification("18:30", this::isNightAttraction)
        );
    }

    private boolean isNightAttraction(Attraction a) {
        String bestTime = String.valueOf(a.getBestTime() == null ? "" : a.getBestTime());
        List<String> tags = a.getTags() == null ? List.of() : a.getTags();
        return "夜景".equals(a.getCategory()) || tags.contains("夜景") || bestTime.contains("18:") || bestTime.contains("19:");
    }

    private boolean isEveningSlot(String time) {
        return time.contains("18:") || time.contains("19:") || time.contains("20:") || time.contains("21:") || time.contains("晚上") || time.contains("夜间");
    }

    private static final Set<String> CENTRAL_URBAN_DISTRICTS = Set.of(
            "渝中区", "江北区", "南岸区", "沙坪坝区", "九龙坡区", "大渡口区", "渝北区", "巴南区", "北碚区"
    );

    private boolean isDistantDistrict(String district) {
        if (district == null || district.isBlank()) return false;
        for (String central : CENTRAL_URBAN_DISTRICTS) {
            if (district.contains(central) || district.contains(central.replace("区", ""))) {
                return false;
            }
        }
        return true;
    }

    private boolean isDistrictMatchingStartPlace(String startPlace, String district) {
        if (startPlace == null || startPlace.isBlank() || "未提供".equals(startPlace) || district == null || district.isBlank()) {
            return false;
        }
        String cleanDist = district.replace("区", "").replace("县", "");
        return startPlace.contains(district) || (!cleanDist.isBlank() && startPlace.contains(cleanDist));
    }

    private String calculateDayDate(TravelConstraints constraints, int day) {
        return TravelDateResolver.resolveItineraryDate(constraints, day);
    }

    private String dateLabel(TravelConstraints c, int day) {
        return (c == null || "未提供".equals(c.getArrivalAt())) ? "旅行第" + day + "天" : c.getArrivalAt() + "起第" + day + "天";
    }

    private boolean isDateMonday(String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        try {
            return java.time.LocalDate.parse(date).getDayOfWeek() == DayOfWeek.MONDAY;
        } catch (Exception e) {
            return false;
        }
    }
}
