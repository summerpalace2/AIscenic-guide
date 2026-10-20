package com.ai.guide.infrastructure.planner.taxonomy;

import java.util.Set;

/** Schema contract: every policy key consumed by planner runtime must be present. */
final class PlannerTaxonomyRequirements {
    private PlannerTaxonomyRequirements() {}
    static final Set<String> PLANNER_SCORE_WEIGHTS = Set.of(
            "builderAvoidPenalty", "builderChildEducation", "builderChildNature", "builderChildTerrain",
            "builderCompanionIndoor", "builderCoupleRomance", "builderCoupleScenic", "builderCoupleThermal",
            "builderDiningFood", "builderDiningPeaceful", "builderDiningPreference", "builderDiningSpicyPenalty",
            "builderExplicitMention", "builderFriendsActive", "builderInterestGeneric", "builderInterestHistory",
            "builderInterestLocal", "builderInterestNature", "builderInterestNight", "builderInterestTerrain",
            "builderInterestThermal", "builderMustVisit", "builderSeniorFriendly", "builderSeniorHard",
            "builderSeniorLowWalking", "builderSoloCalm", "builderWalkAccessible", "builderWalkActive",
            "builderWalkEasy", "builderWalkHard", "builderWalkMedium", "candidateChildFriendly",
            "candidateFoodPreference", "candidateHistoryPreference", "candidateIndoor", "candidateLowWalking",
            "candidateLowWalkingPreference", "candidateNaturePreference", "candidateNightPreference", "candidateOutdoorPenalty",
            "candidateSeniorFriendly", "differentDistrictPenalty", "replacementCulture", "replacementFood",
            "replacementIndoor", "replacementIndoorFeature", "replacementLowWalking", "replacementLowWalkingEvidence",
            "replacementLowWalkingTag", "replacementNight", "replacementNightLowWalking", "replacementSameDistrict",
            "replacementShortVisitTag", "replacementUrgencyDuration", "routeAvoidPenalty", "routeChildEducation",
            "routeChildNature", "routeChildTerrain", "routeCompanionIndoor", "routeCoupleRomance",
            "routeCoupleScenic", "routeCoupleThermal", "routeDiningFood", "routeDiningNonSpicy",
            "routeDiningSpicyPenalty", "routeExplicitMention", "routeFriendsActive", "routeInterestGeneric",
            "routeInterestHistory", "routeInterestLocal", "routeInterestNature", "routeInterestNight",
            "routeInterestTerrain", "routeInterestThermal", "routeLowWalkingPenaltyPerMeter", "routeMustVisit",
            "routeSeniorFriendly", "routeSeniorHard", "routeSeniorLowWalking", "routeSoloCalm",
            "routeTimePreference", "routeTopographyDownhill", "routeTopographyUphill", "routeTravelPenaltyPerMinute",
            "routeWalkAccessible", "routeWalkEasy", "routeWalkHard", "routeWalkMedium",
            "sameCategory", "sameDistrictDefault", "sameDistrictPreferred"
    );
    static final Set<String> RUNTIME_SCORING_PARAMETERS = Set.of(
            "familyFriendly", "historicPoi", "interestGeneric", "interestHistory",
            "interestLocal", "interestNature", "interestNight", "interestTerrain",
            "interestThermal", "lowWalkingClose", "lowWalkingCloseDistanceKm", "lowWalkingDistancePenaltyPerKm",
            "lowWalkingMedium", "lowWalkingMediumDistanceKm", "normalClose", "normalCloseDistanceKm",
            "normalFarDistanceKm", "normalFarDistancePenaltyPerKm", "officialHeat", "prominentPoi",
            "ratingAverage", "ratingAverageThreshold", "ratingExcellent", "ratingExcellentThreshold",
            "ratingGood", "ratingGoodThreshold", "ratingLowPenalty", "ratingLowThreshold",
            "ratingReasonThreshold", "secondaryPoi", "squarePenalty", "strenuousPenalty"
    );
    static final Set<String> PLANNER_LIMITS = Set.of(
            "maxDailyScheduleMinutes", "maxRepairableScenicStops", "maxReplanNonDiningStops", "maxShortTripNearbyClusterCandidates",
            "maxShortTripNeighborhoodCandidates", "maxShortTripSquareCandidates", "maxSingleTransferMinutes", "maxTripDays"
    );
}
