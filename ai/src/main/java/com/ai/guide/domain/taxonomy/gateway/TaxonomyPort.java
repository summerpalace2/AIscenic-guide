package com.ai.guide.domain.taxonomy.gateway;

import java.util.List;
import java.util.Set;
import java.util.Map;

/** Read-only, replaceable dining policy and vocabulary asset boundary. */
public interface TaxonomyPort extends DiningTaxonomyPort, PlannerTaxonomyPort, MemoryTaxonomyPort,
        IntentTaxonomyPort, SearchTaxonomyPort, ChatRetrievalTaxonomyPort, ChatSlotTaxonomyPort {
    static TaxonomyPort empty() {
        return new TaxonomyPort() {
            @Override public MealWindow getMealWindow(String slotType) { return new MealWindow(0, 1440, 0, ""); }
            @Override public CityCuisineProfile getCityProfile(String cityName) { return new CityCuisineProfile(cityName == null ? "" : cityName, "", List.of(), List.of()); }
            @Override public Set<String> getBrands() { return Set.of(); }
            @Override public List<String> getHints(String category) { return List.of(); }
            @Override public List<FlavorAlias> getFlavorAliases() { return List.of(); }
            @Override public DiningSearchPolicy getDiningSearchPolicy() { return DiningSearchPolicy.unconfigured(); }
            @Override public List<IntentRadarRule> getIntentRadarRules() { return List.of(); }
            @Override public boolean isKnownBrand(String brandName) { return false; }
        };
    }
    default List<IntentRadarRule> getIntentRadarRules() { return List.of(); }
    record IntentRadarSuggestion(String action, String label, String icon, String payload) {}
    record IntentRadarRule(String domain, List<String> keywords, String trait, double confidence,
                           List<IntentRadarSuggestion> suggestions) {
        public IntentRadarRule { keywords = List.copyOf(keywords); suggestions = List.copyOf(suggestions); }
    }
    record MemoryFacet(String category, String key, String summary, List<String> aliases) {}
    record AdjustmentWindow(String key, int startMinute, int endMinute, List<String> aliases) {}
    default List<AdjustmentWindow> getAdjustmentWindows() { return List.of(); }
    default Map<String,String> getCityAliases() { return Map.of(); }
    default List<MemoryFacet> getMemoryFacets() { return List.of(); }
    default List<String> getMemoryPresentationWords() { return List.of(); }
    default String getDefaultCity() { return getHints("defaultDestination").stream().findFirst().orElse(""); }
    default Map<String, CategoryPolicyData> getCategoryPolicies() { return Map.of(); }
    default List<QueryAliasGroup> getQueryAliasGroups() { return List.of(); }
    default List<QuerySceneGroup> getQuerySceneGroups() { return List.of(); }
    default Map<String, String> getCityStrategyMappings() { return Map.of(); }
    default List<InterestKeyword> getInterestKeywords() { return List.of(); }
    default List<InterestKeyword> getDietPreferenceKeywords() { return List.of(); }
    record InterestKeyword(String label, List<String> aliases) {
        public InterestKeyword { aliases = List.copyOf(aliases); }
    }
    record QueryAliasGroup(String canonical, List<String> aliases) {
        public QueryAliasGroup { aliases = List.copyOf(aliases); }
    }
    record QuerySceneGroup(String canonical, List<String> aliases) {
        public QuerySceneGroup { aliases = List.copyOf(aliases); }
    }
    record CategoryPolicyData(int minDurationMinutes, int maxDurationMinutes, int defaultDurationMinutes,
                             List<CategoryTimeWindow> preferredTimeWindows, boolean allowedAsFirstStop,
                             boolean allowedAsLastStop, int maxOccurrencesPerDay,
                             int minIntervalMinutesBetweenSameCategory) {
        public CategoryPolicyData { preferredTimeWindows = List.copyOf(preferredTimeWindows); }
    }
    record CategoryTimeWindow(int startMinute, int endMinute, String label) {}
}
