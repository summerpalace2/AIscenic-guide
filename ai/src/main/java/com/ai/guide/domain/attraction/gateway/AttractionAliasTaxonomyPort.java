package com.ai.guide.domain.attraction.gateway;

import java.util.Map;

/** Curated attraction aliases and canonical names supplied by the taxonomy adapter. */
public interface AttractionAliasTaxonomyPort {
    Map<String, String> aliases();
    Map<String, String> canonicalNames();
    long revision();
}
