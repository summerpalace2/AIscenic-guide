package com.ai.guide.domain.attraction.gateway;

import com.ai.guide.domain.attraction.model.Attraction;

import java.util.List;

/** Read-only catalog boundary for curated attractions. */
public interface AttractionCatalogPort {
    List<Attraction> find(String district, String category);
    /** Legacy catalog adapters without city support fail closed for an explicit city. */
    default List<Attraction> findByCity(String city, String district, String category) {
        return city == null || city.isBlank() ? find(district, category) : List.of();
    }
    Attraction findById(String id);
    /** Immutable or legacy adapters keep revision zero. */
    default long revision() { return 0; }
}
