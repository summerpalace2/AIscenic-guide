package com.ai.guide.domain.planner.gateway;

/** Supplies ranking policy independently from the planner vocabulary adapter. */
public interface CandidateRankingPolicyPort {
    record Policy(double compactDistanceKm, double localDistanceKm,
                  int compactDistanceBonus, int localDistanceBonus, double distancePenaltyPerKm,
                  int shortTripBudgetMaximumMinutes, double shortTripFarDistanceKm,
                  int shortTripFarPenalty, double shortTripExtremeDistanceKm,
                  int shortTripExtremePenalty, int unknownLocationPenalty,
                  double contextAverageWeight, double contextNearestWeight) {
        public static Policy unconfigured() { return new Policy(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0); }
        public boolean configured() {
            return compactDistanceKm > 0 && localDistanceKm > compactDistanceKm
                    && distancePenaltyPerKm >= 0 && shortTripBudgetMaximumMinutes > 0
                    && shortTripFarDistanceKm > 0 && shortTripExtremeDistanceKm > shortTripFarDistanceKm
                    && Double.isFinite(contextAverageWeight) && contextAverageWeight > 0
                    && Double.isFinite(contextNearestWeight) && contextNearestWeight > 0
                    && Math.abs(contextAverageWeight + contextNearestWeight - 1.0) < 0.000001;
        }
    }
    default Policy getCandidateRankingPolicy() { return Policy.unconfigured(); }
}
