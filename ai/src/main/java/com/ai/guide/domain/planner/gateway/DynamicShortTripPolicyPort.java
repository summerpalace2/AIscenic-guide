package com.ai.guide.domain.planner.gateway;

import java.util.List;

/** Runtime short-trip planning rules loaded from the planner taxonomy asset. */
public interface DynamicShortTripPolicyPort {
    static Policy unconfigured() {
        return new Policy(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, java.util.Map.of(), List.of(), RouteFallbackPolicy.unconfigured());
    }
    record RouteFallbackPolicy(double distanceSafetyMultiplier,
                               double nearbyDistanceKm, int nearbyMinimumMinutes, double nearbySpeedKmh,
                               double sameDistrictTransitMaximumKm, int sameDistrictWalkingMeters,
                               int sameDistrictMinimumMinutes, double sameDistrictSpeedKmh, int sameDistrictOverheadMinutes,
                               double farDistrictThresholdKm, int farDistrictWalkingMeters,
                               int farDistrictMinimumMinutes, double farDistrictSpeedKmh, int farDistrictOverheadMinutes,
                               int crossDistrictWalkingMeters, int crossDistrictMinimumMinutes,
                               double crossDistrictSpeedKmh, int crossDistrictOverheadMinutes) {
        public static RouteFallbackPolicy unconfigured() {
            return new RouteFallbackPolicy(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);
        }
        public boolean configured() {
            return bounded(distanceSafetyMultiplier, 1, 2)
                    && bounded(nearbyDistanceKm, 0.01, 100) && bounded(nearbyMinimumMinutes, 1, 1440)
                    && bounded(nearbySpeedKmh, 1, 15)
                    && bounded(sameDistrictTransitMaximumKm, 0.01, 500) && bounded(sameDistrictWalkingMeters, 1, 10000)
                    && bounded(sameDistrictMinimumMinutes, 1, 1440) && bounded(sameDistrictSpeedKmh, 1, 160)
                    && bounded(sameDistrictOverheadMinutes, 0, 240)
                    && bounded(farDistrictThresholdKm, nearbyDistanceKm, 1000) && bounded(farDistrictWalkingMeters, 1, 10000)
                    && bounded(farDistrictMinimumMinutes, 1, 1440) && bounded(farDistrictSpeedKmh, 1, 160)
                    && bounded(farDistrictOverheadMinutes, 0, 240)
                    && bounded(crossDistrictWalkingMeters, 1, 10000) && bounded(crossDistrictMinimumMinutes, 1, 1440)
                    && bounded(crossDistrictSpeedKmh, 1, 160) && bounded(crossDistrictOverheadMinutes, 0, 240);
        }
        private static boolean bounded(double value, double minimum, double maximum) {
            return Double.isFinite(value) && value >= minimum && value <= maximum;
        }
        private static boolean bounded(int value, int minimum, int maximum) {
            return value >= minimum && value <= maximum;
        }
    }
    record VisitDurationRule(List<String> nameKeywords, List<String> typeKeywords,
                             int minimumRemainingMinutes, int durationMinutes) {
        public VisitDurationRule {
            nameKeywords = List.copyOf(nameKeywords);
            typeKeywords = List.copyOf(typeKeywords);
        }
        public boolean matches(String name, String type) {
            return (!nameKeywords.isEmpty() && nameKeywords.stream().anyMatch(name::contains))
                    || (!typeKeywords.isEmpty() && typeKeywords.stream().anyMatch(type::contains));
        }
    }

    record Policy(int diningMinimumBudgetMinutes, int stopBudgetBaseMinutes, int minutesPerStop,
                  int minimumStops, int maximumStops, int longParkBudgetMinutes,
                  int shortTripMaxParks, int longTripMaxParks, int minimumStopDurationMinutes,
                  int unknownStopDurationMinutes, int unknownTravelDurationMinutes,
                  int minimumRemainingForStopMinutes, int minimumVisitRemainderMinutes,
                  int diningUrgencyMinimumRemainingMinutes, int diningUrgencyMaximumRemainingMinutes,
                  int explicitFoodDiningLeadMinutes, int minimumRemainingToAddDiningMinutes,
                  double transitPreferredDistanceKm, double estimateTravelMinutesPerKm,
                  int minimumEstimatedTravelMinutes, double duplicateCandidateDistanceKm,
                  java.util.Map<String, Integer> categoryOccurrenceLimits,
                  List<VisitDurationRule> visitDurationRules,
                  RouteFallbackPolicy routeFallbackPolicy) {
        public Policy {
            visitDurationRules = List.copyOf(visitDurationRules);
            categoryOccurrenceLimits = java.util.Map.copyOf(categoryOccurrenceLimits);
            routeFallbackPolicy = routeFallbackPolicy == null ? RouteFallbackPolicy.unconfigured() : routeFallbackPolicy;
        }
        public int maxOccurrences(String category) { return categoryOccurrenceLimits.getOrDefault(category, Integer.MAX_VALUE); }
        public int visitDuration(String name, String type, int remainingMinutes) {
            return visitDurationRules.stream()
                    .filter(rule -> rule.minimumRemainingMinutes() <= remainingMinutes && rule.matches(name, type))
                    .mapToInt(VisitDurationRule::durationMinutes).findFirst()
                    .orElse(unknownStopDurationMinutes);
        }
    }

    Policy getPolicy();
}
