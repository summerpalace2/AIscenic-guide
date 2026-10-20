package com.ai.guide.domain.taxonomy.gateway;

import java.util.List;

/** Vocabulary required to extract coarse user chat slots. */
public interface ChatSlotTaxonomyPort {
    default List<String> getSlotFoodInterestKeywords() { return List.of(); }
    default List<String> getAreaNames() { return List.of(); }

    static ChatSlotTaxonomyPort empty() {
        return new ChatSlotTaxonomyPort() {
            @Override public List<String> getSlotFoodInterestKeywords() { return List.of(); }
            @Override public List<String> getAreaNames() { return List.of(); }
        };
    }
}
