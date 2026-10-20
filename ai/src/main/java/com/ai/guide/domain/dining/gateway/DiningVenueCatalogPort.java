package com.ai.guide.domain.dining.gateway;

import com.ai.guide.domain.dining.model.DiningVenue;

import java.util.List;

/** Read-only catalog boundary for curated dining venues. */
public interface DiningVenueCatalogPort {
    List<DiningVenue> findAll();
}
