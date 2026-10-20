package com.ai.guide.domain.attraction.service;

import com.ai.guide.domain.attraction.gateway.AttractionCatalogPort;
import com.ai.guide.domain.attraction.model.Attraction;
import org.springframework.stereotype.Service;

import java.util.List;

/** Business-facing access to the curated attraction catalog. */
@Service
public class AttractionService {
    private final AttractionCatalogPort catalog;

    public AttractionService(AttractionCatalogPort catalog) {
        this.catalog = catalog;
    }

    public List<Attraction> list(String district, String category) {
        return catalog.find(district, category);
    }

    public List<Attraction> list(String city, String district, String category) {
        return catalog.findByCity(city, district, category);
    }

    public Attraction get(String id) {
        return catalog.findById(id);
    }
    public long revision() { return catalog.revision(); }
}
