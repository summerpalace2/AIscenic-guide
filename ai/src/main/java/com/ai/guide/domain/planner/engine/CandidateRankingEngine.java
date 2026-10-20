package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
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
    private volatile com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy policy = com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy.unconfigured();
    private volatile com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort policyPort;
    private volatile PlannerTaxonomyPort taxonomy;

    public CandidateRankingEngine(RouteCostProvider routeCostProvider) {
        this.routeCostProvider = routeCostProvider;
    }

    public void setPolicy(com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy policy) {
        this.policy = policy == null ? com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy.unconfigured() : policy;
        this.policyPort = null;
    }

    public void setPolicyPort(com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort policyPort) {
        this.policyPort = policyPort;
    }

    private com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy activePolicy() {
        var activePort = policyPort;
        if (activePort == null) return policy;
        var current = activePort.getCandidateRankingPolicy();
        return current == null ? com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy.unconfigured() : current;
    }

    public void setTaxonomy(PlannerTaxonomyPort taxonomy) { this.taxonomy = taxonomy; }

    public int scoreCandidate(Attraction candidate, Attraction old, TravelConstraints constraints, PlanAdjustmentIntent intent) {
        return scoreCandidate(candidate, CandidateContext.of(old), constraints, intent);
    }

    public int scoreCandidate(Attraction candidate, CandidateContext context, TravelConstraints constraints, PlanAdjustmentIntent intent) {
        var activePolicy = activePolicy();
        int score = 0;
        Attraction old = context == null ? null : context.oldAttr();
        boolean preferSameDistrict = (intent != null && intent.getPreferences() != null && intent.getPreferences().contains("SAME_DISTRICT"))
                || (intent != null && intent.getRawMessage() != null && taxonomy != null
                && taxonomy.getHints("sameDistrictRequestAliases").stream().anyMatch(intent.getRawMessage()::contains));

        if (old != null && safe(candidate.getDistrict()).equals(old.getDistrict())) {
            score += PlannerScoringPolicy.weight(taxonomy, preferSameDistrict ? "sameDistrictPreferred" : "sameDistrictDefault");
        } else if (preferSameDistrict) {
            score += PlannerScoringPolicy.weight(taxonomy, "differentDistrictPenalty");
        }

        if (constraints != null && "低".equals(constraints.getWalkingTolerance()) && "低".equals(candidate.getWalkDifficulty())) score += PlannerScoringPolicy.weight(taxonomy, "candidateLowWalking");
        if (old != null && safe(candidate.getCategory()).equals(old.getCategory())) score += PlannerScoringPolicy.weight(taxonomy, "sameCategory");
        if (constraints != null && taxonomy != null && taxonomy.getHints("companionSeniorAliases").contains(constraints.getCompanions())
                && candidate.matchesAnyTagOrFeature(taxonomy.getHints("plannerSeniorFriendlyFeatures").toArray(String[]::new))) score += PlannerScoringPolicy.weight(taxonomy, "candidateSeniorFriendly");
        if (constraints != null && taxonomy != null && taxonomy.getHints("companionChildAliases").contains(constraints.getCompanions())
                && candidate.matchesAnyTagOrFeature(taxonomy.getHints("plannerChildFriendlyFeatures").toArray(String[]::new))) score += PlannerScoringPolicy.weight(taxonomy, "candidateChildFriendly");

        if (intent != null && intent.getPreferences() != null) {
            for (String p : intent.getPreferences()) {
                if ("INDOOR".equalsIgnoreCase(p)) {
                    if (Boolean.TRUE.equals(candidate.getIndoor()) || "INDOOR".equals(candidate.getEffectiveEnvironment())) {
                        score += PlannerScoringPolicy.weight(taxonomy, "candidateIndoor");
                    } else if (!"MIXED".equals(candidate.getEffectiveEnvironment())) {
                        score += PlannerScoringPolicy.weight(taxonomy, "candidateOutdoorPenalty");
                    }
                }
                if ("LOW_WALKING".equalsIgnoreCase(p) && "低".equals(candidate.getWalkDifficulty())) score += PlannerScoringPolicy.weight(taxonomy, "candidateLowWalkingPreference");
                if ("HISTORY".equalsIgnoreCase(p) && matchesConfigured(candidate, "plannerHistoryCategories", "runtimeHistoryInterestFeatures")) score += PlannerScoringPolicy.weight(taxonomy, "candidateHistoryPreference");
                if ("NIGHT_VIEW".equalsIgnoreCase(p) && matchesConfigured(candidate, "plannerNightCategories", "plannerNightTags")) score += PlannerScoringPolicy.weight(taxonomy, "candidateNightPreference");
                if ("FOOD".equalsIgnoreCase(p) && matchesConfigured(candidate, "plannerFoodCategories", "foodTags")) score += PlannerScoringPolicy.weight(taxonomy, "candidateFoodPreference");
                if ("NATURE".equalsIgnoreCase(p) && matchesConfigured(candidate, "plannerNatureCategories", "runtimeNatureInterestFeatures")) score += PlannerScoringPolicy.weight(taxonomy, "candidateNaturePreference");
            }
        }

        // 空间几何距离打分与超距惩罚
        double distKm = calculateDistanceToContext(candidate, context, activePolicy);
        if (!Double.isFinite(distKm) && activePolicy.configured()) {
            score -= activePolicy.unknownLocationPenalty();
        }
        if (activePolicy.configured() && Double.isFinite(distKm)) {
            if (distKm <= activePolicy.compactDistanceKm()) {
                score += activePolicy.compactDistanceBonus();
            } else if (distKm <= activePolicy.localDistanceKm()) {
                score += activePolicy.localDistanceBonus();
            } else {
                score -= (int) Math.round((distKm - activePolicy.localDistanceKm()) * activePolicy.distancePenaltyPerKm());
            }
        }

        // 限时规划（如 <= 360分钟 / 6小时）：出行时间极为紧缺，对远距离施加强力惩罚
        boolean isShortTimeBudget = activePolicy.configured() && constraints != null && constraints.getTimeBudgetMinutes() > 0
                && constraints.getTimeBudgetMinutes() <= activePolicy.shortTripBudgetMaximumMinutes();
        if (isShortTimeBudget && Double.isFinite(distKm)) {
            if (distKm > activePolicy.shortTripFarDistanceKm()) {
                score -= activePolicy.shortTripFarPenalty();
            }
            if (distKm > activePolicy.shortTripExtremeDistanceKm()) {
                score -= activePolicy.shortTripExtremePenalty();
            }
        }

        return score;
    }

    private boolean matchesConfigured(Attraction candidate, String categoryKey, String featureKey) {
        PlannerTaxonomyPort activeTaxonomy = taxonomy;
        if (activeTaxonomy == null) return false;
        List<String> categories = activeTaxonomy.getHints(categoryKey);
        List<String> features = activeTaxonomy.getHints(featureKey);
        return categories.stream().anyMatch(category -> category.equals(candidate.getCategory()))
                || candidate.matchesAnyTagOrFeature(features.toArray(String[]::new));
    }

    public double calculateDistanceToContext(Attraction candidate, CandidateContext context) {
        return calculateDistanceToContext(candidate, context, activePolicy());
    }

    private double calculateDistanceToContext(Attraction candidate, CandidateContext context,
            com.ai.guide.domain.planner.gateway.CandidateRankingPolicyPort.Policy activePolicy) {
        if (candidate == null || candidate.getLocation() == null || !candidate.getLocation().contains(",")) {
            return Double.POSITIVE_INFINITY;
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
            return Double.POSITIVE_INFINITY;
        }
        double sum = 0;
        double min = Double.MAX_VALUE;
        for (double d : distances) {
            sum += d;
            if (d < min) min = d;
        }
        double avg = sum / distances.size();
        if (!activePolicy.configured()) return Double.POSITIVE_INFINITY;
        return avg * activePolicy.contextAverageWeight() + min * activePolicy.contextNearestWeight();
    }

    public boolean isAvoided(Attraction attraction, TravelConstraints constraints) {
        if (constraints == null || constraints.getAvoid() == null) return false;
        return constraints.getAvoid().stream().anyMatch(avoid ->
                attraction.getName().contains(avoid)
                        || attraction.getId().contains(avoid)
                        || avoid.equals(attraction.getCategory()));
    }

    public boolean isOpeningCompatible(Attraction attraction, String slotTime, ItineraryTimeEngine timeEngine) {
        return attraction == null || OpeningHoursPolicy.isTimeOpen(OpeningHoursPolicy.evidence(attraction.getOpeningHours(), attraction.getBestTime()), slotTime);
    }

    public boolean isOpeningCompatible(Attraction attraction, String slotTime, String date, ItineraryTimeEngine timeEngine) {
        String evidence = attraction == null ? "" : OpeningHoursPolicy.evidence(attraction.getOpeningHours(), attraction.getBestTime());
        return attraction == null || (OpeningHoursPolicy.isTimeOpen(evidence, slotTime)
                && !OpeningHoursPolicy.isClosed(evidence, date, slotTime));
    }

    public static boolean isClosedOnDate(Attraction attraction, String date) {
        return attraction != null && OpeningHoursPolicy.isClosed(OpeningHoursPolicy.evidence(attraction.getOpeningHours(), attraction.getBestTime()), date, null);
    }

    private String safe(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }
}
