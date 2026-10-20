package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.engine.CandidateRankingEngine;
import com.ai.guide.domain.planner.engine.ItineraryTimeEngine;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.engine.RouteCost;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.engine.PlannerScoringPolicy;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.*;

/**
 * 单日排程重排、雨天室内切换与密度减负策略
 * （覆盖 REPLAN_DAY, PLAN, REDUCE_DAY_DENSITY, REPLAN_DAY_FOR_CONDITION 意图）
 */
@Component
public class ReplanDayAdjustmentStrategy implements AdjustmentStrategy {

    private record RainCandidateScore(Attraction attraction, double score) {}

    private final AttractionService attractionService;
    private final ItineraryBuilderPort itineraryBuilder;
    private final RouteAwarePlanner routeAwarePlanner;
    private final CandidateRankingEngine candidateRankingEngine;
    private final ItineraryTimeEngine itineraryTimeEngine;
    private final RouteCostProvider routeCostProvider;
    private PlannerTaxonomyPort taxonomy = TaxonomyPort.empty();

    @Autowired
    public ReplanDayAdjustmentStrategy(AttractionService attractionService,
                                      ItineraryBuilderPort itineraryBuilder,
                                      RouteAwarePlanner routeAwarePlanner,
                                      CandidateRankingEngine candidateRankingEngine,
                                      ItineraryTimeEngine itineraryTimeEngine,
                                      RouteCostProvider routeCostProvider) {
        this.attractionService = attractionService;
        this.itineraryBuilder = itineraryBuilder;
        this.routeAwarePlanner = routeAwarePlanner;
        this.candidateRankingEngine = candidateRankingEngine;
        this.itineraryTimeEngine = itineraryTimeEngine;
        this.routeCostProvider = routeCostProvider;
    }

    public void setTaxonomy(PlannerTaxonomyPort taxonomy) {
        this.taxonomy = taxonomy == null ? TaxonomyPort.empty() : taxonomy;
    }

    @Override
    public boolean supports(ConversationIntentType intentType) {
        return intentType == ConversationIntentType.REPLAN_DAY
                || intentType == ConversationIntentType.PLAN
                || intentType == ConversationIntentType.REDUCE_DAY_DENSITY
                || intentType == ConversationIntentType.REPLAN_DAY_FOR_CONDITION;
    }

    @Override
    public void apply(AdjustmentContext ctx) {
        ConversationIntentType type = ctx.getIntent().getType();
        switch (type) {
            case REPLAN_DAY_FOR_CONDITION -> handleRainReplan(ctx);
            case REDUCE_DAY_DENSITY -> handleReduceDensity(ctx);
            case REPLAN_DAY -> handleReplanDay(ctx);
            case PLAN -> handlePlanFallback(ctx);
            default -> {}
        }
    }

    private void handleRainReplan(AdjustmentContext ctx) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = ctx.getAttractionsById();

        int dayNumber = intent.getDayNumber() == null ? 1 : intent.getDayNumber();
        Map<String, Object> day = findDay(draftTrip, dayNumber);
        if (day == null) {
            ctx.setFatalError(error(400, "目标日期不存在。", ctx));
            return;
        }

        List<Map<String, Object>> stops = new ArrayList<>(dayStops(day));
        Set<String> pinned = new HashSet<>(intent.getPinnedStopIds() == null ? List.of() : intent.getPinnedStopIds());
        Set<String> mustVisit = new HashSet<>(constraints.getMustVisit() == null ? List.of() : constraints.getMustVisit());
        Set<String> tripUsed = usedVenues(draftTrip);
        for (Map<String, Object> s : stops) tripUsed.remove(String.valueOf(s.get("venueId")));

        ctx.getAdjustmentDiagnostics().put("type", "RAIN_REPLAN");
        ctx.getAdjustmentDiagnostics().put("day", dayNumber);
        ctx.getAdjustmentDiagnostics().put("originalStops", describeStops(stops, byId));
        ctx.getAdjustmentDiagnostics().put("pinnedStopIds", new ArrayList<>(pinned));
        ctx.getAdjustmentDiagnostics().put("mustVisit", new ArrayList<>(mustVisit));

        List<Map<String, Object>> newStops = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Map<String, Object> originalStop = stops.get(i);
            String stopId = String.valueOf(originalStop.get("id"));

            if (isProtectedStop(originalStop, pinned, constraints)) {
                tripUsed.add(String.valueOf(originalStop.get("venueId")));
                newStops.add(originalStop);
                continue;
            }

            Attraction originalAttr = byId.get(safe(originalStop.get("venueId")));
            if (originalAttr != null && "INDOOR".equalsIgnoreCase(originalAttr.getEffectiveEnvironment())) {
                tripUsed.add(originalAttr.getId());
                newStops.add(originalStop);
                continue;
            }

            Attraction rainCandidate = findRainCandidate(stops, i, tripUsed, constraints, byId);
            if (rainCandidate != null) {
                tripUsed.add(rainCandidate.getId());
                Map<String, Object> replaced = itineraryBuilder.createStop(
                        rainCandidate, stopId,
                        String.valueOf(originalStop.getOrDefault("time", "待安排")),
                        rainCandidate.getDuration(),
                        "因雨天/同区室内优先调整为" + rainCandidate.getName(),
                        rainCandidate.getIntro()
                );
                newStops.add(replaced);
                ctx.getChangedSegments().add(stopId);
            } else {
                newStops.add(originalStop);
            }
        }
        day.put("stops", newStops);
        List<Map<String, Object>> resolvedDays = resolveAffectedDay(draftTrip, dayNumber, byId, constraints);
        restoreProtectedRainStops(resolvedDays, dayNumber, newStops, pinned, constraints);
        routeAwarePlanner.backfillAdjacentEdgeCosts(resolvedDays, byId, constraints.getTransportPreference());
        ctx.getAdjustmentDiagnostics().put("replacedStops", describeStops(newStops, byId));
        ctx.getAdjustmentDiagnostics().put("routeEdges", describeRouteEdges(findDay(draftTrip, dayNumber), byId));
        ctx.getOptionPlans().put("option-1", draftTrip);
    }

    private void handleReduceDensity(AdjustmentContext ctx) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = ctx.getAttractionsById();

        int dayNumber = intent.getDayNumber() == null ? 1 : intent.getDayNumber();
        Map<String, Object> day = findDay(draftTrip, dayNumber);
        if (day == null) {
            ctx.setFatalError(error(400, "目标日期不存在。", ctx));
            return;
        }

        List<Map<String, Object>> stops = dayStops(day);
        int count = intent.getReduceCount() == null ? 1 : intent.getReduceCount();
        Set<String> pinned = new HashSet<>(intent.getPinnedStopIds() == null ? List.of() : intent.getPinnedStopIds());
        if (constraints.getMustVisit() != null) pinned.addAll(constraints.getMustVisit());

        int removed = 0;
        for (int i = stops.size() - 1; i >= 0 && removed < count && stops.size() > 1; i--) {
            Map<String, Object> stop = stops.get(i);
            String venueId = String.valueOf(stop.get("venueId"));
            String stopId = String.valueOf(stop.get("id"));
            if (!pinned.contains(venueId) && !pinned.contains(stopId)) {
                stops.remove(i);
                ctx.getChangedSegments().add(stopId);
                removed++;
            }
        }
        resolveAffectedDay(draftTrip, dayNumber, byId, constraints);
        ctx.getOptionPlans().put("option-1", draftTrip);
    }

    private void handleReplanDay(AdjustmentContext ctx) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = ctx.getAttractionsById();

        Set<String> pinned = new HashSet<>(intent.getPinnedStopIds() == null ? List.of() : intent.getPinnedStopIds());
        if ("TRIP".equalsIgnoreCase(intent.getScope())) {
            for (Map<String, Object> day : days(draftTrip)) {
                Integer dayNumber = number(day.get("day"));
                if (dayNumber != null) replanDay(draftTrip, dayNumber, byId, constraints, intent, pinned, ctx.getChangedSegments());
            }
        } else {
            int dayNumber = intent.getDayNumber() == null ? 1 : intent.getDayNumber();
            if (findDay(draftTrip, dayNumber) == null) {
                ctx.setFatalError(error(400, "目标日期不存在。", ctx));
                return;
            }
            replanDay(draftTrip, dayNumber, byId, constraints, intent, pinned, ctx.getChangedSegments());
        }
        ctx.getOptionPlans().put("option-1", draftTrip);
    }

    private void handlePlanFallback(AdjustmentContext ctx) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        TravelConstraints constraints = ctx.getConstraints();
        Map<String, Attraction> byId = ctx.getAttractionsById();

        Set<String> pinned = new HashSet<>(intent.getPinnedStopIds() == null ? List.of() : intent.getPinnedStopIds());
        int dayNumber = intent.getDayNumber() != null ? intent.getDayNumber()
                : (ctx.getPageContext().getActiveDay() != null ? ctx.getPageContext().getActiveDay() : 1);
        if (findDay(draftTrip, dayNumber) == null) {
            dayNumber = 1;
        }
        if (findDay(draftTrip, dayNumber) != null) {
            replanDay(draftTrip, dayNumber, byId, constraints, intent, pinned, ctx.getChangedSegments());
        }
        ctx.getOptionPlans().put("option-1", draftTrip);
    }

    public void replanDay(Map<String, Object> draftTrip,
                          int dayNumber,
                          Map<String, Attraction> byId,
                          TravelConstraints constraints,
                          PlanAdjustmentIntent intent,
                          Set<String> pinned,
                          List<String> changedSegments) {
        Map<String, Object> day = findDay(draftTrip, dayNumber);
        if (day == null) return;

        List<Map<String, Object>> stops = new ArrayList<>(dayStops(day));
        Set<String> tripUsed = usedVenues(draftTrip);
        List<Map<String, Object>> preservedStops = stops.stream().map(s -> (Map<String, Object>) new LinkedHashMap<String, Object>(s)).toList();
        boolean scoped = intent != null && intent.getTargetStartMinute() != null && intent.getTargetEndMinute() != null;
        for (Map<String, Object> stop : stops)
            if (!scoped || inScope(stop, intent)) tripUsed.remove(String.valueOf(stop.get("venueId")));

        Attraction requestedTargetAttr = null;
        if (intent != null && intent.getTargetAttractionId() != null && !intent.getTargetAttractionId().isBlank()) {
            requestedTargetAttr = byId.get(intent.getTargetAttractionId());
        }
        boolean targetInjected = false;

        List<Map<String, Object>> newStops = new ArrayList<>();
        for (Map<String, Object> originalStop : stops) {
            String stopId = String.valueOf(originalStop.get("id"));
            if (isProtectedStop(originalStop, pinned, constraints) || (scoped && (!inScope(originalStop, intent) || isDiningStop(originalStop, taxonomy)))) {
                tripUsed.add(String.valueOf(originalStop.get("venueId")));
                newStops.add(originalStop);
                continue;
            }

            String slotTime = String.valueOf(originalStop.getOrDefault("time", ""));
            int stopIdx = stops.indexOf(originalStop);
            Attraction prevAttr = stopIdx > 0 ? resolveStopAttraction(stops.get(stopIdx - 1), itineraryBuilder) : null;
            Attraction nextAttr = stopIdx + 1 < stops.size() ? resolveStopAttraction(stops.get(stopIdx + 1), itineraryBuilder) : null;
            String startCoord = extractStartCoordinate(draftTrip, constraints);
            CandidateRankingEngine.CandidateContext context = new CandidateRankingEngine.CandidateContext(
                    byId.get(String.valueOf(originalStop.get("venueId"))),
                    prevAttr, nextAttr, startCoord);

            Attraction replacement = null;
            if (requestedTargetAttr != null && !targetInjected && !isDiningStop(originalStop, taxonomy)) {
                replacement = requestedTargetAttr;
                targetInjected = true;
            } else {
                List<Attraction> candidates = findCandidates(context, tripUsed, constraints, intent, slotTime);
                if (candidates.isEmpty()) {
                    newStops.add(originalStop);
                    continue;
                }
                replacement = candidates.get(0);
            }

            tripUsed.add(replacement.getId());
            newStops.add(itineraryBuilder.createStop(
                    replacement,
                    stopId,
                    String.valueOf(originalStop.getOrDefault("time", "待安排")),
                    replacement.getDuration(),
                    "根据偏好调整为" + replacement.getName() + "（" + PlannerService.stripTrailingPunctuation(replacement.getSummary()) + "）。",
                    replacement.getIntro()
            ));
            changedSegments.add(stopId);
        }

        if (requestedTargetAttr != null && !targetInjected && !scoped) {
            String newStopId = "day" + dayNumber + "-added-" + requestedTargetAttr.getId();
            Map<String, Object> added = itineraryBuilder.createStop(
                    requestedTargetAttr,
                    newStopId,
                    "待安排",
                    requestedTargetAttr.getDuration(),
                    "根据偏好新增为" + requestedTargetAttr.getName() + "（" + PlannerService.stripTrailingPunctuation(requestedTargetAttr.getSummary()) + "）。",
                    requestedTargetAttr.getIntro()
            );
            newStops.add(0, added);
            changedSegments.add(newStopId);
            tripUsed.add(requestedTargetAttr.getId());
        }

        // Apply the configured day density policy while preserving pinned and protected stops.
        int nonDiningCount = 0;
        for (Map<String, Object> s : newStops) {
            if (!isDiningStop(s, taxonomy)) nonDiningCount++;
        }
        int maximumNonDiningStops = PlannerScoringPolicy.limit(taxonomy, "maxReplanNonDiningStops");
        while (!scoped && maximumNonDiningStops > 0 && nonDiningCount > maximumNonDiningStops) {
            int removeIdx = -1;
            for (int i = newStops.size() - 1; i >= 0; i--) {
                Map<String, Object> s = newStops.get(i);
                if (!isDiningStop(s, taxonomy) && !isProtectedStop(s, pinned, constraints)) {
                    removeIdx = i;
                    break;
                }
            }
            if (removeIdx >= 0) {
                newStops.remove(removeIdx);
                nonDiningCount--;
            } else {
                break;
            }
        }

        day.put("stops", newStops);
        List<Map<String, Object>> resolvedDays = scoped ? List.of(day) : resolveAffectedDay(draftTrip, dayNumber, byId, constraints);
        if (scoped) {
            // Restore protected business fields before deriving routes from the final
            // predecessor. Their old incoming edge belongs to the old route.
            for (int i = 0; i < stops.size(); i++)
                if (!inScope(stops.get(i), intent) || isDiningStop(stops.get(i), taxonomy) || isProtectedStop(stops.get(i), pinned, constraints))
                    newStops.set(i, preservedStops.get(i));
            day.put("stops", newStops);
        }
        if (scoped) routeAwarePlanner.backfillAdjacentEdgeCosts(resolvedDays, byId,
                constraints.getTransportPreference(), List.of(Map.of("day", day.get("day"), "stops", preservedStops)));
        else routeAwarePlanner.backfillAdjacentEdgeCosts(resolvedDays, byId, constraints.getTransportPreference());
    }

    private boolean inScope(Map<String, Object> stop, PlanAdjustmentIntent intent) {
        String time = safe(stop.getOrDefault("time", stop.get("startTime")));
        if (!time.matches("\\d{1,2}:\\d{2}")) return false;
        String[] parts = time.split(":");
        int hour = Integer.parseInt(parts[0]), minute = Integer.parseInt(parts[1]);
        if (hour > 23 || minute > 59) return false;
        int value = hour * 60 + minute;
        return value >= intent.getTargetStartMinute() && value < intent.getTargetEndMinute();
    }

    private List<Attraction> findCandidates(CandidateRankingEngine.CandidateContext context,
                                            Set<String> used,
                                            TravelConstraints constraints,
                                            PlanAdjustmentIntent intent,
                                            String slotTime) {
        List<Attraction> catalog = attractionService.list(null, null);
        if (intent != null && intent.getPreferences() != null && intent.getPreferences().contains("NIGHT_VIEW")) {
            List<String> categories = taxonomy.getHints("plannerNightCategories");
            String[] features = taxonomy.getHints("plannerNightTags").toArray(String[]::new);
            catalog = catalog.stream().filter(a -> (a.getCategory() != null && categories.contains(a.getCategory()))
                    || a.matchesAnyTagOrFeature(features)).toList();
        }
        boolean userRequestedSameDistrict = (intent != null && intent.getPreferences() != null && intent.getPreferences().contains("SAME_DISTRICT"))
                || (intent != null && intent.getRawMessage() != null && (intent.getRawMessage().contains("同片区") || intent.getRawMessage().contains("同区")));

        Attraction oldAttr = context != null ? context.oldAttr() : null;
        String targetDistrict = oldAttr != null && oldAttr.getDistrict() != null && !oldAttr.getDistrict().isBlank()
                ? oldAttr.getDistrict() : "";

        if (userRequestedSameDistrict && !targetDistrict.isBlank() && !"待确认".equals(targetDistrict)) {
            List<Attraction> sameDistrictCandidates = catalog.stream()
                    .filter(a -> !used.contains(a.getId()) && (oldAttr == null || !a.getId().equals(oldAttr.getId())))
                    .filter(a -> safe(a.getDistrict()).equals(targetDistrict) || targetDistrict.contains(safe(a.getDistrict())) || safe(a.getDistrict()).contains(targetDistrict))
                    .filter(a -> candidateRankingEngine.isOpeningCompatible(a, slotTime, itineraryTimeEngine))
                    .map(a -> new CandidateRankingEngine.ScoredCandidate(a, candidateRankingEngine.scoreCandidate(a, context, constraints, intent)))
                    .sorted(Comparator.comparingInt(CandidateRankingEngine.ScoredCandidate::score).reversed()
                            .thenComparingInt(sc -> -itineraryBuilder.catalogRank(sc.attraction().getId())))
                    .map(CandidateRankingEngine.ScoredCandidate::attraction)
                    .limit(3)
                    .toList();
            if (!sameDistrictCandidates.isEmpty()) {
                return sameDistrictCandidates;
            }
        }

        return catalog.stream()
                .filter(a -> !used.contains(a.getId()) && (oldAttr == null || !a.getId().equals(oldAttr.getId())))
                .filter(a -> candidateRankingEngine.isOpeningCompatible(a, slotTime, itineraryTimeEngine))
                .map(a -> new CandidateRankingEngine.ScoredCandidate(a, candidateRankingEngine.scoreCandidate(a, context, constraints, intent)))
                .sorted(Comparator.comparingInt(CandidateRankingEngine.ScoredCandidate::score).reversed()
                        .thenComparingInt(sc -> -itineraryBuilder.catalogRank(sc.attraction().getId())))
                .map(CandidateRankingEngine.ScoredCandidate::attraction)
                .limit(3)
                .toList();
    }

    private Attraction findRainCandidate(List<Map<String, Object>> originalStops,
                                         int index,
                                         Set<String> used,
                                         TravelConstraints constraints,
                                         Map<String, Attraction> byId) {
        Map<String, Object> originalStop = originalStops.get(index);
        Attraction original = byId.get(safe(originalStop.get("venueId")));
        Attraction previous = index > 0 ? byId.get(safe(originalStops.get(index - 1).get("venueId"))) : null;
        Attraction next = index + 1 < originalStops.size()
                ? byId.get(safe(originalStops.get(index + 1).get("venueId"))) : null;

        String slotTime = String.valueOf(originalStop.getOrDefault("time", originalStop.getOrDefault("startTime", "")));

        List<Attraction> candidates = attractionService.list(null, null).stream()
                .filter(a -> !used.contains(a.getId()))
                .filter(a -> original == null || !a.getId().equals(original.getId()))
                .filter(a -> "INDOOR".equals(a.getEffectiveEnvironment()) || "MIXED".equals(a.getEffectiveEnvironment()))
                .filter(a -> !candidateRankingEngine.isAvoided(a, constraints))
                .filter(a -> candidateRankingEngine.isOpeningCompatible(a, slotTime, itineraryTimeEngine))
                .toList();

        if (original != null) {
            List<Attraction> sameDistrict = candidates.stream()
                    .filter(a -> safe(a.getDistrict()).equals(safe(original.getDistrict())))
                    .toList();
            if (!sameDistrict.isEmpty()) candidates = sameDistrict;
        }

        String preference = constraints == null ? "walking" : constraints.getTransportPreference();
        return candidates.stream()
                .map(candidate -> new RainCandidateScore(candidate, scoreRainCandidate(candidate, original, previous, next, preference)))
                .filter(scored -> scored.score() > -1000)
                .max(Comparator.comparingDouble(RainCandidateScore::score)
                        .thenComparingInt(scored -> -itineraryBuilder.catalogRank(scored.attraction().getId())))
                .map(RainCandidateScore::attraction)
                .orElse(null);
    }

    private double scoreRainCandidate(Attraction candidate,
                                      Attraction original,
                                      Attraction previous,
                                      Attraction next,
                                      String preference) {
        double score = "INDOOR".equals(candidate.getEffectiveEnvironment()) ? rainScore("rainIndoor") : rainScore("rainMixed");
        if (original != null && safe(candidate.getDistrict()).equals(safe(original.getDistrict()))) score += rainScore("rainSameAsOriginal");
        if (previous != null && safe(candidate.getDistrict()).equals(safe(previous.getDistrict()))) score += rainScore("rainSameAsPrevious");
        if (next != null && safe(candidate.getDistrict()).equals(safe(next.getDistrict()))) score += rainScore("rainSameAsNext");
        if (taxonomy.getHints("walkingEasyLevels").contains(candidate.getWalkDifficulty())) score += rainScore("rainLowWalking");

        List<Attraction> neighbors = new ArrayList<>();
        if (previous != null) neighbors.add(previous);
        if (next != null) neighbors.add(next);
        for (Attraction neighbor : neighbors) {
            RouteCost cost;
            try {
                cost = neighbor == previous
                        ? routeCostProvider.calculate(neighbor, candidate, preference)
                        : routeCostProvider.calculate(candidate, neighbor, preference);
            } catch (RuntimeException e) {
                score += rainScore("rainRouteErrorPenalty");
                continue;
            }
            if (cost == null || cost.status() == RouteCost.RouteDataStatus.UNAVAILABLE) {
                score += rainScore("rainRouteUnavailablePenalty");
            } else {
                long minutes = cost.duration() == null ? 0 : cost.duration().toMinutes();
                if (minutes > rainScore("rainMaximumRouteMinutes")) return rainScore("rainInfeasibleScore");
                score -= Math.min(rainScore("rainRoutePenaltyCapMinutes"), minutes) * rainScore("rainRoutePenaltyPerMinute");
            }
        }
        return score;
    }

    private double rainScore(String key) { return PlannerScoringPolicy.parameter(taxonomy, key); }

    private void restoreProtectedRainStops(List<Map<String, Object>> resolvedDays,
                                           int dayNumber,
                                           List<Map<String, Object>> proposedStops,
                                           Set<String> pinned,
                                           TravelConstraints constraints) {
        Map<String, Object> targetDay = null;
        for (Map<String, Object> candidate : resolvedDays) {
            if (Integer.valueOf(dayNumber).equals(number(candidate.get("day")))) {
                targetDay = candidate;
                break;
            }
        }
        if (targetDay == null) return;
        List<Map<String, Object>> repairedStops = dayStops(targetDay);
        for (int index = 0; index < proposedStops.size(); index++) {
            Map<String, Object> protectedStop = proposedStops.get(index);
            if (!isProtectedStop(protectedStop, pinned, constraints)) continue;
            String stopId = safe(protectedStop.get("id"));
            boolean present = repairedStops.stream().anyMatch(existing ->
                    stopId.equals(safe(existing.get("id")))
                            || safe(protectedStop.get("venueId")).equals(safe(existing.get("venueId"))));
            if (!present) repairedStops.add(Math.min(index, repairedStops.size()), protectedStop);
        }
    }

    private List<Map<String, Object>> describeStops(List<Map<String, Object>> stops,
                                                    Map<String, Attraction> byId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> stop : stops) {
            Attraction attraction = byId.get(safe(stop.get("venueId")));
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("id", safe(stop.get("id")));
            description.put("venueId", safe(stop.get("venueId")));
            description.put("name", safe(stop.get("name")));
            description.put("district", attraction == null ? "" : safe(attraction.getDistrict()));
            description.put("environment", attraction == null ? "" : attraction.getEffectiveEnvironment());
            description.put("routeDataStatus", safe(stop.get("routeDataStatus")));
            result.add(description);
        }
        return result;
    }

    private List<Map<String, Object>> describeRouteEdges(Map<String, Object> day,
                                                         Map<String, Attraction> byId) {
        List<Map<String, Object>> edges = new ArrayList<>();
        if (day == null) return edges;
        List<Map<String, Object>> stops = dayStops(day);
        for (int i = 1; i < stops.size(); i++) {
            Map<String, Object> previous = stops.get(i - 1);
            Map<String, Object> current = stops.get(i);
            RouteCost cost = RouteCost.fromMap(current.get("resolvedRouteCost"));
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("fromStopId", safe(previous.get("id")));
            edge.put("toStopId", safe(current.get("id")));
            edge.put("fromVenueId", safe(previous.get("venueId")));
            edge.put("toVenueId", safe(current.get("venueId")));
            edge.put("fromDistrict", safe(byId.containsKey(safe(previous.get("venueId")))
                    ? byId.get(safe(previous.get("venueId"))).getDistrict() : ""));
            edge.put("toDistrict", safe(byId.containsKey(safe(current.get("venueId")))
                    ? byId.get(safe(current.get("venueId"))).getDistrict() : ""));
            edge.put("durationMinutes", cost == null || cost.duration() == null ? null : cost.duration().toMinutes());
            edge.put("routeDataStatus", cost == null || cost.status() == null ? "UNAVAILABLE" : cost.status().name());
            edges.add(edge);
        }
        return edges;
    }

    private List<Map<String, Object>> resolveAffectedDay(Map<String, Object> trip, Integer dayNumber,
                                                         Map<String, Attraction> byId, TravelConstraints constraints) {
        Map<String, Object> affected = dayNumber == null ? null : findDay(trip, dayNumber);
        if (affected == null) return List.of();
        return routeAwarePlanner.resolveAndVerifyDays(new ArrayList<>(List.of(affected)), byId, constraints);
    }

    private PlannerService.ServiceResult error(int status, String message, AdjustmentContext ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        if (ctx != null && ctx.getSession() != null) {
            body.put("sessionId", ctx.getSession().sessionId());
            body.put("trip", ctx.getSession().trip());
            body.put("appliedPreferences", ctx.getSession().appliedPreferences().asMap());
        }
        return new PlannerService.ServiceResult(status, body);
    }
}
