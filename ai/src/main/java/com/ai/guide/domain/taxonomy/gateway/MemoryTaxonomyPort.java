package com.ai.guide.domain.taxonomy.gateway;

import java.util.List;

/** User-memory projection vocabulary owned by the memory domain. */
public interface MemoryTaxonomyPort extends LegacyHintTaxonomyPort {
    List<TaxonomyPort.MemoryFacet> getMemoryFacets();
    List<String> getMemoryPresentationWords();
    List<TaxonomyPort.InterestKeyword> getDietPreferenceKeywords();
}
