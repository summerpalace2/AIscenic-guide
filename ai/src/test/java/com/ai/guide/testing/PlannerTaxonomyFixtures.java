package com.ai.guide.testing;

import com.ai.guide.domain.planner.engine.LocalPlanRepairer;
import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.service.ConversationIntentClassifier;
import com.ai.guide.domain.planner.service.PoiCategoryPolicyRegistry;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;

/** Shared test fixture backed by the same bundled assets used by production. */
public final class PlannerTaxonomyFixtures {
    private PlannerTaxonomyFixtures() {}

    public static PoiCategoryPolicyRegistry categoryPolicies() {
        return new PoiCategoryPolicyRegistry(new JsonTaxonomyAdapter());
    }

    public static JsonTaxonomyAdapter taxonomy() { return new JsonTaxonomyAdapter(); }

    public static ConversationIntentClassifier classifier() {
        try (var input = PlannerTaxonomyFixtures.class.getClassLoader().getResourceAsStream("attractions.json")) {
            if (input == null) throw new IllegalStateException("missing attraction fixture");
            List<Attraction> places = new ObjectMapper().readValue(input, new TypeReference<>() {});
            Map<String, Attraction> byId = places.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Attraction::getId, place -> place));
            AttractionService catalog = new AttractionService(new AttractionCatalogPort() {
                @Override public List<Attraction> find(String district, String category) {
                    return places.stream().filter(place -> district == null || district.isBlank() || district.equals(place.getDistrict()))
                            .filter(place -> category == null || category.isBlank() || category.equals(place.getCategory())).toList();
                }
                @Override public Attraction findById(String id) { return byId.get(id); }
            });
            JsonTaxonomyAdapter taxonomy = new JsonTaxonomyAdapter();
            ConversationIntentClassifier classifier = new ConversationIntentClassifier(catalog,
                    new AttractionAliasMatcher(catalog, taxonomy));
            classifier.setTaxonomy(taxonomy);
            classifier.setDiningCategoryGrounder(new com.ai.guide.domain.planner.engine.DiningCategoryGrounder(taxonomy));
            return classifier;
        } catch (Exception error) {
            throw new IllegalStateException("cannot load planner taxonomy fixture", error);
        }
    }

    public static LocalPlanRepairer localPlanRepairer() {
        LocalPlanRepairer repairer = new LocalPlanRepairer(categoryPolicies());
        repairer.setTaxonomy(taxonomy());
        return repairer;
    }
}
