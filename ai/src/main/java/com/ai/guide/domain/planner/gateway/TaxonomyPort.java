package com.ai.guide.domain.planner.gateway;

import java.util.List;
import java.util.Set;
import java.util.Map;

/** Read-only, replaceable dining policy and vocabulary asset boundary. */
public interface TaxonomyPort extends BrandDictionaryPort {
    record MealWindow(int startMinute, int endMinute, int idealMinute, String defaultLabel) {}
    record CityCuisineProfile(String cityName, String defaultBreakfast, List<String> signatureDishes, List<String> breakfastKeywords) {}
    record FlavorAlias(String label, List<String> keywords, String searchKeyword, List<String> poiTypeKeywords) {
        public FlavorAlias(String label, List<String> keywords) { this(label, keywords, null, List.of()); }
        public FlavorAlias {
            keywords = List.copyOf(keywords);
            poiTypeKeywords = List.copyOf(poiTypeKeywords);
        }
    }
    record MemoryFacet(String category, String key, String summary, List<String> aliases) {}
    record AdjustmentWindow(String key, int startMinute, int endMinute, List<String> aliases) {}
    default List<AdjustmentWindow> getAdjustmentWindows() { return List.of(); }
    MealWindow getMealWindow(String slotType);
    CityCuisineProfile getCityProfile(String cityName);
    Set<String> getBrands();
    List<String> getHints(String category);
    List<FlavorAlias> getFlavorAliases();
    default Map<String,String> getCityAliases() { return Map.of(); }
    default List<MemoryFacet> getMemoryFacets() { return List.of(); }
    default List<String> getMemoryPresentationWords() { return List.of(); }
}
