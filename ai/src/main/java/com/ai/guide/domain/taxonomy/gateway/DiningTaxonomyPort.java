package com.ai.guide.domain.taxonomy.gateway;

import java.util.List;
import java.util.Set;
import java.util.Map;

/** Dining-owned taxonomy capabilities. */
public interface DiningTaxonomyPort extends BrandDictionaryPort, LegacyHintTaxonomyPort {
    static DiningTaxonomyPort empty() {
        return new DiningTaxonomyPort() {
            @Override public MealWindow getMealWindow(String slotType) { return new MealWindow(0, 1440, 0, ""); }
            @Override public CityCuisineProfile getCityProfile(String cityName) {
                return new CityCuisineProfile(cityName == null ? "" : cityName, "", List.of(), List.of());
            }
            @Override public Set<String> getBrands() { return Set.of(); }
            @Override public List<String> getHints(String category) { return List.of(); }
            @Override public List<FlavorAlias> getFlavorAliases() { return List.of(); }
            @Override public boolean isKnownBrand(String brandName) { return false; }
        };
    }

    record MealWindow(int startMinute, int endMinute, int idealMinute, String defaultLabel) {}
    record CityCuisineProfile(String cityName, String defaultBreakfast, List<String> signatureDishes,
                              List<String> breakfastKeywords) {}
    record FlavorAlias(String label, List<String> keywords, String searchKeyword, List<String> poiTypeKeywords) {
        public FlavorAlias(String label, List<String> keywords) { this(label, keywords, null, List.of()); }
        public FlavorAlias {
            keywords = List.copyOf(keywords);
            poiTypeKeywords = List.copyOf(poiTypeKeywords);
        }
    }
    record DiningSearchPolicy(int proximityRadiusMeters, int nearbyRadiusMeters, int expansionStepMeters, int fallbackRadiusMeters,
                              String breakfastTypes, String mealTypes, String teaBreakTypes,
                              String genericMealKeyword, String genericTeaBreakKeyword, String fallbackPoiType,
                              String diningTypePrefix, List<String> nonMealTypeKeywords,
                              int proximityTimeBudgetMinutes, double proximityMaxDistanceKm,
                              double sameDistrictMaxDistanceKm, double suburbanMaxDistanceKm,
                              double crossDistrictMaxDistanceKm, double localFallbackMaxDistanceKm,
                              int veryNearDistanceMeters, double nearDistanceKm, double mediumDistanceKm,
                              int districtMatchScore, int exactFlavorScore, int citySignatureScore, int mismatchPenalty,
                              List<Integer> distanceBandScores, int elderFriendlyBonus, int lowWalkingBonus,
                              double lowWalkingDistanceKm, Map<String, Integer> mealDurationsMinutes) {
        public DiningSearchPolicy {
            nonMealTypeKeywords = List.copyOf(nonMealTypeKeywords);
            distanceBandScores = List.copyOf(distanceBandScores);
            mealDurationsMinutes = Map.copyOf(mealDurationsMinutes);
        }
        public static DiningSearchPolicy unconfigured() {
            return new DiningSearchPolicy(0, 0, 0, 0, "", "", "", "", "", "", "", List.of(),
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, Map.of());
        }
        public boolean configured() {
            return proximityRadiusMeters > 0 && nearbyRadiusMeters > 0 && expansionStepMeters > 0
                    && fallbackRadiusMeters > 0 && !breakfastTypes.isBlank() && !mealTypes.isBlank()
                    && !teaBreakTypes.isBlank() && !genericMealKeyword.isBlank() && !fallbackPoiType.isBlank()
                    && !diningTypePrefix.isBlank() && proximityTimeBudgetMinutes > 0
                    && proximityMaxDistanceKm > 0 && sameDistrictMaxDistanceKm > 0 && suburbanMaxDistanceKm > 0
                    && crossDistrictMaxDistanceKm > 0 && localFallbackMaxDistanceKm > 0
                    && veryNearDistanceMeters > 0 && nearDistanceKm > 0 && mediumDistanceKm > nearDistanceKm
                    && distanceBandScores.size() == 4 && !mealDurationsMinutes.isEmpty();
        }
    }

    MealWindow getMealWindow(String slotType);
    CityCuisineProfile getCityProfile(String cityName);
    Set<String> getBrands();
    List<FlavorAlias> getFlavorAliases();
    default DiningSearchPolicy getDiningSearchPolicy() { return DiningSearchPolicy.unconfigured(); }
    default List<String> getSuburbanDistricts(String cityName) { return List.of(); }
}
