package com.ai.guide.domain.planner.model.itinerary;

import com.ai.guide.domain.planner.model.category.PoiDomainType;

import java.util.Map;

/**
 * 候选替换点位实体
 */
public record CandidateReplacementItem(
        String venueId,
        String name,
        PoiDomainType type,
        String reason,
        double matchScore,
        Map<String, Object> extra
) {}
