package com.ai.guide.domain.attraction.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttractionVisitDurationTest {
    @Test
    void missingVisitDurationRemainsUnknown() {
        assertEquals(0, Attraction.builder().duration("建议游览").build().getEffectiveVisitMinutes());
        assertEquals(0, Attraction.builder().build().getEffectiveVisitMinutes());
        assertEquals(75, Attraction.builder().recommendedVisitMinutes(75).build().getEffectiveVisitMinutes());
    }
}
