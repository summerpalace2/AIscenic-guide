package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.planner.service.PlannerMetricsLogger;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.ScoreBreakdown;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.ai.guide.domain.planner.service.TravelDateResolver;
import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
import com.ai.guide.domain.planner.model.topography.ElevationFlow;
import com.ai.guide.domain.planner.service.TopographyStrategyRouter;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.planner.service.strategy.ChongqingTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.GenericPlainTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.ChengduPlainTopographyStrategy;
import com.ai.guide.domain.planner.service.strategy.XiAnHeritageTopographyStrategy;
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
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
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
    private final TopographyStrategyRouter topographyStrategyRouter;
    private final PlannerTaxonomyPort taxonomy;

    @Autowired
    public RouteAwarePlanner(RouteCostProvider routeCostProvider,
                             PlanVerifier planVerifier,
                             LocalPlanRepairer localPlanRepairer,
                             @Autowired(required = false) PlannerMetricsLogger metricsLogger,
                             @Autowired(required = false) TopographyStrategyRouter topographyStrategyRouter,
                             PlannerTaxonomyPort taxonomy) {
        this.routeCostProvider = routeCostProvider;
        this.planVerifier = planVerifier;
        this.localPlanRepairer = localPlanRepairer;
        this.metricsLogger = metricsLogger != null ? metricsLogger : new PlannerMetricsLogger();
        this.topographyStrategyRouter = topographyStrategyRouter != null ? topographyStrategyRouter
                : new TopographyStrategyRouter(List.of(new ChongqingTopographyStrategy(),new ChengduPlainTopographyStrategy(),
                        new XiAnHeritageTopographyStrategy(),new GenericPlainTopographyStrategy()));
        this.taxonomy = taxonomy == null ? TaxonomyPort.empty() : taxonomy;
    }

    public RouteAwarePlanner(RouteCostProvider routeCostProvider, PlanVerifier planVerifier,
                             LocalPlanRepairer localPlanRepairer, PlannerMetricsLogger metricsLogger,
                             TopographyStrategyRouter topographyStrategyRouter) {
        this(routeCostProvider, planVerifier, localPlanRepairer, metricsLogger, topographyStrategyRouter, TaxonomyPort.empty());
    }

    public RouteAwarePlanner(RouteCostProvider routeCostProvider,
                             PlanVerifier planVerifier,
                             LocalPlanRepairer localPlanRepairer) {
        this(routeCostProvider, planVerifier, localPlanRepairer, new PlannerMetricsLogger(), null);
    }

    private TopographyStrategyPort resolveTopographyStrategy(TravelConstraints constraints) {
        return topographyStrategyRouter.route(constraints == null ? null : constraints.getDestination());
    }

    private boolean usesBaselineCatalogPolicy(TravelConstraints constraints) {
        return "CHONGQING".equalsIgnoreCase(resolveTopographyStrategy(constraints).supportedCity());
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
        List<String> configuredThemes = taxonomy.getHints(usesBaselineCatalogPolicy(constraints) ? "baselineDayThemes" : "genericDayThemes");
        String[] themes = configuredThemes.toArray(String[]::new);

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
                        dayDate,
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
                        String topographyTransitHint = resolveTopographyStrategy(constraints).generateTransitCharacteristicHint(
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
            String dayTheme = themes.length == 0 ? "" : themes[(day - 1) % themes.length];
            dayMap.put("day", day);
            dayMap.put("date", dayDate == null ? dateLabel(constraints, day) : dayDate);
            dayMap.put("dateLabel", dayDate == null
                    ? "第" + day + "天" + (dayTheme.isBlank() ? "" : " · " + dayTheme)
                    : dayDate + " · 第" + day + "天" + (dayTheme.isBlank() ? "" : " · " + dayTheme));
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
            List<Map<String, Object>> nextDays = localPlanRepairer.repair(currentDays, verification, constraints, catalogList);
            for (int d = 0; d < Math.min(currentDays.size(), nextDays.size()); d++) {
                if (currentDays.get(d).get("stops") instanceof List<?> before
                        && nextDays.get(d).get("stops") instanceof List<?> after && !before.isEmpty() && !after.isEmpty()
                        && before.get(0) instanceof Map<?,?> oldFirst && after.get(0) instanceof Map<?,?> newFirst
                        && !Objects.equals(oldFirst.get("id"), newFirst.get("id"))) {
                    @SuppressWarnings("unchecked") Map<String,Object> first = (Map<String,Object>) newFirst;
                    invalidateIncomingRoute(first);
                }
            }
            if (Objects.equals(currentDays, nextDays)) {
                break;
            }
            currentDays = nextDays;
        }

        return currentDays;
    }

    public void backfillAdjacentEdgeCosts(List<Map<String, Object>> days,
                                         Map<String, Attraction> byId,
                                         String preference) {
        backfillAdjacentEdgeCosts(days, byId, preference, null);
    }

    /** A local edit only invalidates edges whose endpoints actually changed. */
    public void backfillAdjacentEdgeCosts(List<Map<String, Object>> days, Map<String, Attraction> byId,
                                         String preference, List<Map<String, Object>> originalDays) {
        Map<Object, List<Map<String, Object>>> originals = new LinkedHashMap<>();
        if (originalDays != null) for (var originalDay : originalDays) {
            if (originalDay.get("stops") instanceof List<?> originalStops) {
                @SuppressWarnings("unchecked") List<Map<String,Object>> typed = (List<Map<String,Object>>) originalStops;
                originals.put(originalDay.get("day"), typed);
            }
        }
        if (days == null) return;
        for (Map<String, Object> day : days) {
            Object stopsObj = day.get("stops");
            if (!(stopsObj instanceof List<?> list)) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) list;
            for (int i = 1; i < stops.size(); i++) {
                Map<String, Object> prevStop = stops.get(i - 1);
                Map<String, Object> currStop = stops.get(i);
                List<Map<String,Object>> originalStops = originals.get(day.get("day"));
                if (originalStops != null) {
                    boolean unchanged = false;
                    for (int j = 1; j < originalStops.size(); j++) {
                        if (sameRouteEndpoint(prevStop, originalStops.get(j - 1))
                                && sameRouteEndpoint(currStop, originalStops.get(j))) { unchanged = true; break; }
                    }
                    if (unchanged) continue;
                }
                Attraction prevAttraction = routeEndpoint(prevStop, byId);
                Attraction currAttraction = routeEndpoint(currStop, byId);
                // Incoming metadata belongs to the old adjacency until this edge is resolved.
                invalidateIncomingRoute(currStop);
                RouteCost resolved = prevAttraction == null || currAttraction == null
                        ? RouteCost.unavailable("当前相邻站点坐标缺失，接驳待核验")
                        : routeCostProvider.resolveFinalEdge(prevAttraction, currAttraction, preference);
                if (resolved == null) resolved = RouteCost.unavailable("当前相邻接驳待核验");
                currStop.put("resolvedRouteCost", resolved.asMap());
                currStop.put("estimatedRouteCost", resolved.asMap());
                currStop.put("routeDataStatus", resolved.status().name());
                String routeSummary = resolved.status() == RouteCost.RouteDataStatus.UNAVAILABLE
                        ? "接驳待核验" : "交通约 " + resolved.asMap().get("durationMinutes") + " 分钟";
                currStop.put("walk", routeSummary);
                if (resolved.status() != RouteCost.RouteDataStatus.UNAVAILABLE) {
                    Map<String, Object> selected = new LinkedHashMap<>(resolved.asMap());
                    selected.put("mode", resolved.transportMode());
                    currStop.put("routeFromPrevious", Map.of("selectedMode", resolved.transportMode(),
                            "routeDataStatus", resolved.status().name(), "summary", routeSummary, "selected", selected));
                }
            }
        }
    }

    /** Remove only incoming-route assets; retain venue coordinates and accessibility facts. */
    public static void invalidateIncomingRoute(Map<String,Object> stop) {
        for (String key : List.of("routeFromPrevious", "resolvedRouteCost", "estimatedRouteCost", "routeDataStatus", "walk"))
            stop.remove(key);
        for (String key : List.of("walkingInfo", "mapContext")) {
            if (stop.get(key) instanceof Map<?,?> original) {
                Map<String,Object> retained = new LinkedHashMap<>();
                original.forEach((k,v) -> retained.put(String.valueOf(k),v));
                for (String field : List.of("routeFromPrevious", "polyline", "summary", "duration", "durationSeconds", "durationMinutes",
                        "distance", "distanceMeters", "walkingDistanceMeters", "walkingMeters", "selected", "selectedMode",
                        "routeDataStatus", "status", "reason", "mode", "transportMode")) retained.remove(field);
                if (retained.isEmpty()) stop.remove(key); else stop.put(key, retained);
            }
        }
    }

    private boolean sameRouteEndpoint(Map<String,Object> left, Map<String,Object> right) {
        return Objects.equals(left.get("id"), right.get("id"))
                && Objects.equals(left.get("venueId"), right.get("venueId"))
                && Objects.equals(left.get("location"), right.get("location"));
    }

    private Attraction routeEndpoint(Map<String, Object> stop, Map<String, Attraction> byId) {
        String venueId = stop.get("venueId") == null ? "" : String.valueOf(stop.get("venueId"));
        Attraction catalog = byId == null ? null : byId.get(venueId);
        String coordinate = stop.get("location") == null ? "" : String.valueOf(stop.get("location"));
        if (!com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.isValidCoordinate(coordinate))
            coordinate = catalog == null ? "" : catalog.getLocation();
        if (!com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.isValidCoordinate(coordinate)) return null;
        return Attraction.builder().id(venueId.isBlank() ? "route-stop-" + stop.get("id") : venueId)
                .name(String.valueOf(stop.getOrDefault("name", "")))
                .district(String.valueOf(stop.getOrDefault("district", catalog == null ? "" : catalog.getDistrict())))
                .location(coordinate).amapQuery(catalog == null ? Map.of() : catalog.getAmapQuery()).build();
    }

    private CandidateEvaluation selectBestCandidate(Collection<Attraction> catalog,
                                                    Set<String> used,
                                                    List<Attraction> currentDayAttractions,
                                                    Attraction previous,
                                                    SlotSpecification slot,
                                                    String dayDate,
                                                    TravelConstraints constraints,
                                                    List<String> explicitIds,
                                                    int day) {
        CandidateEvaluation best = null;
        double maxUtility = -Double.MAX_VALUE;

        for (Attraction candidate : catalog) {
            if (used.contains(candidate.getId())) continue;

            // Hard constraint 0: Distant district requires explicit mention or matching start place
            boolean isCandidateDistant = usesBaselineCatalogPolicy(constraints) && isDistantDistrict(candidate.getDistrict());
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

            String opening = OpeningHoursPolicy.evidence(candidate.getOpeningHours(), candidate.getBestTime());
            if (OpeningHoursPolicy.isClosed(opening, dayDate, slot.time()) || !OpeningHoursPolicy.isTimeOpen(opening, slot.time())) continue;
            if (isNightAttraction(candidate) && !isEveningSlot(slot.time())) {
                continue; // Cannot schedule night attraction at morning/afternoon slot
            }

            // Hard constraint 4: Cross-district half-day collision (GS-01)
            if (previous != null && usesBaselineCatalogPolicy(constraints)) {
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

            if (routeCost == null || routeCost.duration() == null || routeCost.status() == RouteCost.RouteDataStatus.UNAVAILABLE) continue;
            double travelPenalty = routeCost.duration().toMinutes()
                    * PlannerScoringPolicy.parameter(taxonomy, "routeTravelPenaltyPerMinute");
            double walkingPenalty = isLowWalking(constraints)
                    ? routeCost.walkingMeters() * PlannerScoringPolicy.parameter(taxonomy, "routeLowWalkingPenaltyPerMeter") : 0;
            double timePreferenceBonus = slot.preferredPredicate().test(candidate) ? scoreWeight("routeTimePreference") : 0.0;

            // 1. 折返惩罚（Zig-zag Penalty）：杜绝同一天在“山顶 ↔ 滨江”反复横跳或频繁来回跨江
            TopographyStrategyPort topoStrategy = resolveTopographyStrategy(constraints);
            double zigZagPenalty = topoStrategy.calculateZigZagPenalty(currentDayAttractions, candidate);

            // 2. 顺势而下加分与爬坡减分（Downhill First for Low-Walking / Elder-Friendly）
            double topographyBonus = 0.0;
            if (previous != null) {
                boolean isLowWalking = isLowWalking(constraints);
                ElevationFlow flow = topoStrategy.detectElevationFlow(previous, candidate);
                if (isLowWalking) {
                    if (flow == ElevationFlow.DOWNHILL) {
                        topographyBonus += scoreWeight("routeTopographyDownhill");
                    } else if (flow == ElevationFlow.UPHILL) {
                        topographyBonus += scoreWeight("routeTopographyUphill");
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
        int explicitBonus = explicitIds != null && explicitIds.contains(a.getId()) ? scoreWeight("routeExplicitMention") : 0;
        int mustVisitScore = (c.getMustVisit() != null && c.getMustVisit().stream().anyMatch(m -> a.getName().contains(m) || a.getId().contains(m))) ? scoreWeight("routeMustVisit") : 0;
        int avoidPenalty = (c.getAvoid() != null && c.getAvoid().stream().anyMatch(av -> a.getName().contains(av) || a.getId().contains(av) || av.equals(a.getCategory()))) ? scoreWeight("routeAvoidPenalty") : 0;

        int interestScore = 0;
        for (String interest : c.getInterests()) {
            if (taxonomy.getHints("plannerNatureInterestLabels").contains(interest)) {
                if (matchesConfigured(a, "plannerNatureCategories", "plannerNatureFeatures")) {
                    interestScore += scoreWeight("routeInterestNature"); reasonCodes.add("INTEREST_NATURE");
                }
            } else if (taxonomy.getHints("plannerHistoryInterestLabels").contains(interest)) {
                if (matchesConfigured(a, "plannerHistoryCategories", "plannerHistoryFeatures")) {
                    interestScore += scoreWeight("routeInterestHistory"); reasonCodes.add("INTEREST_HISTORY");
                }
            } else if (taxonomy.getHints("chongqingTerrainInterestLabels").contains(interest) && usesBaselineCatalogPolicy(c)) {
                if (matchesFeatures(a, "chongqingTerrainInterestFeatures")) {
                    interestScore += scoreWeight("routeInterestTerrain"); reasonCodes.add("INTEREST_8D_MAGIC");
                }
            } else if (taxonomy.getHints("plannerNightInterestLabels").contains(interest)) {
                if (matchesConfigured(a, "plannerNightCategories", "plannerNightScoringFeatures")) {
                    interestScore += scoreWeight("routeInterestNight"); reasonCodes.add("INTEREST_NIGHT_VIEW");
                }
            } else if (taxonomy.getHints("plannerThermalInterestLabels").contains(interest)) {
                if (matchesConfigured(a, "plannerThermalCategories", "plannerThermalFeatures") || matchesName(a, "plannerThermalNameTerms")) {
                    interestScore += scoreWeight("routeInterestThermal"); reasonCodes.add("INTEREST_HOTSPRING");
                }
            } else if (taxonomy.getHints("plannerLocalInterestLabels").contains(interest)) {
                if (matchesConfigured(a, "plannerLocalCategories", "plannerLocalCultureScoringFeatures")) {
                    interestScore += scoreWeight("routeInterestLocal"); reasonCodes.add("INTEREST_LOCAL_LIFE");
                }
            } else if (a.matchesAnyTagOrFeature(interest) || interest.equals(a.getCategory()) || a.getName().contains(interest)) {
                interestScore += scoreWeight("routeInterestGeneric"); reasonCodes.add("INTEREST_TAG_MATCH");
            }
        }

        int walkingScore = 0;
        if (taxonomy.getHints("walkingLowAliases").contains(c.getWalkingTolerance())) {
            if (taxonomy.getHints("walkingEasyLevels").contains(a.getWalkDifficulty()) || "SUPPORTED".equals(a.getEffectiveAccessibility())) walkingScore += scoreWeight("routeWalkEasy");
            if (matchesFeatures(a, "plannerWalkingAccessibleFeatures")) walkingScore += scoreWeight("routeWalkAccessible");
            if (taxonomy.getHints("walkingHardLevels").contains(a.getWalkDifficulty())) walkingScore += scoreWeight("routeWalkHard");
            if (taxonomy.getHints("walkingMediumLevels").contains(a.getWalkDifficulty())) walkingScore += scoreWeight("routeWalkMedium");
        }

        int companionScore = 0;
        String companions = c.getCompanions();
        if (matchesHint(companions, "plannerCompanionSeniorAliases")) {
            companionScore += taxonomy.getHints("indoorEnvironmentValues").contains(a.getEffectiveEnvironment()) ? scoreWeight("routeCompanionIndoor") : 0;
            if (matchesFeatures(a, "plannerCompanionSeniorFeatures")) companionScore += scoreWeight("routeSeniorFriendly");
            if (taxonomy.getHints("walkingEasyLevels").contains(a.getWalkDifficulty())) companionScore += scoreWeight("routeSeniorLowWalking");
            if (taxonomy.getHints("walkingHardLevels").contains(a.getWalkDifficulty())) companionScore += scoreWeight("routeSeniorHard");
        } else if (matchesHint(companions, "plannerCompanionChildAliases")) {
            companionScore += taxonomy.getHints("indoorEnvironmentValues").contains(a.getEffectiveEnvironment()) ? scoreWeight("routeCompanionIndoor") : 0;
            if (matchesFeatures(a, "plannerCompanionChildEducationFeatures")) companionScore += scoreWeight("routeChildEducation");
            if (matchesConfigured(a, "plannerNatureCategories", "plannerCompanionChildNatureFeatures")) companionScore += scoreWeight("routeChildNature");
            if (usesBaselineCatalogPolicy(c) && matchesFeatures(a, "chongqingTerrainInterestFeatures")) companionScore += scoreWeight("routeChildTerrain");
        } else if (matchesHint(companions, "plannerCompanionCoupleAliases")) {
            if (matchesConfigured(a, "plannerNightCategories", "plannerCompanionCoupleRomanceFeatures")) companionScore += scoreWeight("routeCoupleRomance");
            if (matchesFeatures(a, "plannerCompanionCoupleThermalFeatures") || matchesName(a, "plannerThermalNameTerms")) companionScore += scoreWeight("routeCoupleThermal");
            if (matchesFeatures(a, "plannerCompanionCoupleScenicFeatures")) companionScore += scoreWeight("routeCoupleScenic");
        } else if (matchesHint(companions, "plannerCompanionFriendsAliases")) {
            if ((usesBaselineCatalogPolicy(c) && matchesFeatures(a, "chongqingTerrainInterestFeatures"))
                    || matchesConfigured(a, "plannerCompanionFriendsCategories", "plannerCompanionFriendsFeatures")) companionScore += scoreWeight("routeFriendsActive");
        } else if (matchesHint(companions, "plannerCompanionSoloAliases")) {
            if (matchesConfigured(a, "plannerCreativeCategories", "plannerCompanionSoloFeatures")) companionScore += scoreWeight("routeSoloCalm");
        }

        int diningScore = 0;
        boolean avoidsSpicy = c.getDietPreference() != null
                && taxonomy.getHints("nonSpicyFlavors").stream().anyMatch(c.getDietPreference()::contains);
        if (avoidsSpicy) {
            if (taxonomy.getHints("indoorEnvironmentValues").contains(a.getEffectiveEnvironment()) || matchesFeatures(a, "plannerDiningPeacefulFeatures")) diningScore += scoreWeight("routeDiningNonSpicy");
            if (taxonomy.getHints("spicyFlavors").stream().anyMatch(a::matchesAnyTagOrFeature)) diningScore += scoreWeight("routeDiningSpicyPenalty");
        } else if (matchesConfiguredFlavor(a, c.getDietPreference())) {
            if (matchesConfigured(a, "plannerFoodCategories", "foodTags")) diningScore += scoreWeight("routeDiningFood");
        }
        int districtScore = 0;
        // 起点邻近加分（START_PLACE_DISTRICT_PROXIMITY）仅在第1天起步阶段生效；
        // 当后续已有行程且面临跨江时，严禁因初始起点区位加分而强制跨江拉扯
        TopographyStrategyPort topoStrategy = resolveTopographyStrategy(c);
        boolean allowStartPlaceProximity = (day == 1)
                && topoStrategy.allowStartPlaceProximity(previous, a);
        if (allowStartPlaceProximity && usesBaselineCatalogPolicy(c) && c.getStartPlace() != null && !c.getStartPlace().isBlank() && !"未提供".equals(c.getStartPlace())) {
            int startPlaceBonus = startPlaceDistrictBonus(c.getStartPlace(), a);
            if (startPlaceBonus > 0) {
                districtScore += startPlaceBonus + configuredDistrictBoost(a);
                reasonCodes.add("START_PLACE_DISTRICT_PROXIMITY");
            }
        }

        int totalScore = explicitBonus + mustVisitScore + avoidPenalty + interestScore + walkingScore + companionScore + diningScore + districtScore;
        return new ScoreBreakdown(interestScore, companionScore, walkingScore, diningScore, mustVisitScore, avoidPenalty, explicitBonus, totalScore, reasonCodes);
    }

    private int scoreWeight(String key) { return PlannerScoringPolicy.weight(taxonomy, key); }
    private boolean isLowWalking(TravelConstraints constraints) {
        if (constraints == null) return false;
        if (taxonomy.getHints("walkingLowAliases").contains(constraints.getWalkingTolerance())) return true;
        String companions = constraints.getCompanions();
        if (companions != null && taxonomy.getHints("plannerCompanionSeniorAliases").stream()
                .anyMatch(alias -> companions.contains(alias) || alias.contains(companions))) return true;
        String prompt = constraints.getRawPrompt();
        return prompt != null && taxonomy.getHints("plannerLowWalkingPromptAliases").stream().anyMatch(prompt::contains);
    }
    private boolean matchesHint(String value, String key) { return value != null && taxonomy.getHints(key).contains(value); }
    private boolean matchesFeatures(Attraction attraction, String key) {
        return attraction != null && attraction.matchesAnyTagOrFeature(taxonomy.getHints(key).toArray(String[]::new));
    }
    private boolean matchesName(Attraction attraction, String key) {
        return attraction != null && taxonomy.getHints(key).stream().anyMatch(term -> attraction.getName().contains(term));
    }
    private boolean matchesConfigured(Attraction attraction, String categoryKey, String featureKey) {
        return attraction != null && (taxonomy.getHints(categoryKey).contains(attraction.getCategory()) || matchesFeatures(attraction, featureKey));
    }

    private int startPlaceDistrictBonus(String startPlace, Attraction attraction) {
        for (String rule : taxonomy.getHints("startPlaceDistrictRules")) {
            String[] fields = rule.split("\\|", 3);
            if (fields.length != 3 || !fields[0].equals(attraction.getDistrict())) continue;
            if (!java.util.Arrays.stream(fields[1].split(",")).anyMatch(startPlace::contains)) continue;
            try { return Integer.parseInt(fields[2]); }
            catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    private int configuredDistrictBoost(Attraction attraction) {
        for (String rule : taxonomy.getHints("startPlaceDistrictBoostRules")) {
            String[] fields = rule.split("\\|", 3);
            if (fields.length != 3 || !fields[0].equals(attraction.getDistrict())) continue;
            if (!java.util.Arrays.stream(fields[1].split(",")).anyMatch(keyword -> attraction.getName().contains(keyword)
                    || attraction.getId().contains(keyword.toLowerCase(java.util.Locale.ROOT)))) continue;
            try { return Integer.parseInt(fields[2]); }
            catch (NumberFormatException ignored) { return 0; }
        }
        return 0;
    }

    private record SlotSpecification(String time, Predicate<Attraction> preferredPredicate) {}

    /** Capacity from the same schedule policy used by the actual selector. */
    public int expectedAttractionSlots(int day, int dayCount, TravelConstraints constraints) {
        return buildDaySlots(day, dayCount, constraints).size();
    }

    private List<SlotSpecification> buildDaySlots(int day, int dayCount, TravelConstraints constraints) {
        boolean isFirstDay = (day == 1);
        boolean isLastDay = (day == dayCount);
        String arrival = constraints == null ? "" : constraints.getArrivalAt();
        String departure = constraints == null ? "" : constraints.getDepartureAt();

        // Day 1 Arrival window constraint (GS-04); schedule policy is supplied by taxonomy assets.
        if (isFirstDay && (arrival.contains("晚上") || arrival.contains("夜间"))) {
            return configuredSlots("scheduleArrivalNight");
        }
        if (isFirstDay && (arrival.contains("下午"))) {
            return configuredSlots("scheduleArrivalAfternoon");
        }

        // Final Day Departure window constraint
        if (isLastDay && (departure.contains("上午") || departure.contains("早晨"))) {
            return configuredSlots("scheduleDepartureMorning");
        }
        if (isLastDay && (departure.contains("中午") || departure.contains("下午"))) {
            return configuredSlots("scheduleDepartureMidday");
        }

        // Standard 3-slot day
        return configuredSlots("scheduleStandardDay");
    }

    private List<SlotSpecification> configuredSlots(String key) {
        List<String> configured = taxonomy.getHints(key);
        if (configured.isEmpty()) return List.of();
        return configured.stream().map(value -> {
            String[] fields = value.split("\\|", 2);
            if (fields.length != 2 || fields[0].isBlank()) throw new IllegalStateException("Invalid schedule slot asset: " + key);
            Predicate<Attraction> preferred = "NIGHT".equalsIgnoreCase(fields[1]) ? this::isNightAttraction
                    : "DAY".equalsIgnoreCase(fields[1]) ? attraction -> !isNightAttraction(attraction)
                    : throwInvalidSlotType(key, fields[1]);
            return new SlotSpecification(fields[0], preferred);
        }).toList();
    }

    private Predicate<Attraction> throwInvalidSlotType(String key, String type) {
        throw new IllegalStateException("Invalid schedule slot type " + type + " in " + key);
    }

    private boolean isNightAttraction(Attraction a) {
        String bestTime = String.valueOf(a.getBestTime() == null ? "" : a.getBestTime());
        List<String> tags = a.getTags() == null ? List.of() : a.getTags();
        return taxonomy.getHints("plannerNightCategories").contains(a.getCategory())
                || tags.stream().anyMatch(tag -> taxonomy.getHints("plannerNightScoringFeatures").stream().anyMatch(tag::contains))
                || isWithinConfiguredNightWindow(bestTime);
    }

    private boolean isEveningSlot(String time) {
        return taxonomy.getHints("plannerNightWords").stream().anyMatch(time::contains)
                || isWithinConfiguredNightWindow(time);
    }

    private boolean matchesConfiguredFlavor(Attraction attraction, String preference) {
        if (attraction == null || preference == null || preference.isBlank()) return false;
        return taxonomy.getFlavorAliases().stream()
                .filter(alias -> alias.label().equals(preference) || alias.keywords().contains(preference))
                .flatMap(alias -> alias.keywords().stream())
                .anyMatch(attraction::matchesAnyTagOrFeature);
    }

    private boolean isWithinConfiguredNightWindow(String value) {
        if (value == null || value.isBlank()) return false;
        Matcher time = Pattern.compile("(\\d{1,2})[:：](\\d{2})").matcher(value);
        while (time.find()) {
            int minute = Integer.parseInt(time.group(1)) * 60 + Integer.parseInt(time.group(2));
            for (String window : taxonomy.getHints("plannerNightTimeWindow")) {
                String[] bounds = window.split("\\|", 2);
                if (bounds.length == 2 && minute >= Integer.parseInt(bounds[0]) && minute < Integer.parseInt(bounds[1])) return true;
            }
        }
        return false;
    }

    private boolean isDistantDistrict(String district) {
        if (district == null || district.isBlank()) return false;
        for (String central : taxonomy.getHints("centralUrbanDistricts")) {
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
