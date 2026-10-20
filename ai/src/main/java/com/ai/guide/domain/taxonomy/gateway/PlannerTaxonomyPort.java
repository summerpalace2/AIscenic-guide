package com.ai.guide.domain.taxonomy.gateway;

import java.util.List;
import java.util.Map;

/** Planning-owned time-window, city-strategy and attraction-category policies. */
public interface PlannerTaxonomyPort extends DiningTaxonomyPort, IntentTaxonomyPort, LegacyHintTaxonomyPort {
    default int getDefaultDurationDays() {
        try {
            int maxDays = getHints("plannerLimits").stream()
                    .filter(value -> value.startsWith("maxTripDays="))
                    .map(value -> value.substring("maxTripDays=".length()))
                    .findFirst().map(Integer::parseInt).orElse(0);
            return getHints("defaultDurationDays").stream().findFirst().map(Integer::parseInt)
                    .filter(days -> days >= 1 && maxDays > 0 && days <= maxDays).orElse(0);
        } catch (RuntimeException invalidPolicy) {
            return 0;
        }
    }
    List<TaxonomyPort.AdjustmentWindow> getAdjustmentWindows();
    Map<String, TaxonomyPort.CategoryPolicyData> getCategoryPolicies();
    Map<String, String> getCityStrategyMappings();
    Map<String, String> getCityAliases();
    java.util.List<TaxonomyPort.InterestKeyword> getDietPreferenceKeywords();
}
