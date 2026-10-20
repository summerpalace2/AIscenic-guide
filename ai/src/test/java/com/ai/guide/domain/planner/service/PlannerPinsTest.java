package com.ai.guide.domain.planner.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PlannerPinsTest {
    Map<String,Object> trip(String venue, boolean present) {
        return new LinkedHashMap<>(Map.of("days", List.of(Map.of("day", 1, "stops",
                present ? List.of(new LinkedHashMap<>(Map.of("id", "a", "venueId", venue, "name", "A"))) : List.of())),
                "pinnedStopIds", List.of("a")));
    }
    @Test void missingLegacyFieldReadsEmpty() { assertEquals(List.of(), PlannerPins.read(Map.of())); }
    @Test void deletionAndReplacementRejectedEvenWithSameStopId() {
        var before = trip("v-a", true);
        assertFalse(PlannerPins.retained(before, trip("v-a", false)));
        assertFalse(PlannerPins.retained(before, trip("v-b", true)));
        assertTrue(PlannerPins.retained(before, trip("v-a", true)));
    }
    @Test void restoreCannotClearOrInjectProtection() {
        var before = trip("v-a", true);
        var after = trip("v-a", true);
        after.put("pinnedStopIds", List.of("foreign"));
        PlannerPins.preserve(before, after);
        assertEquals(List.of("a"), PlannerPins.read(after));
        var legacy = new LinkedHashMap<String,Object>();
        PlannerPins.preserve(legacy, after);
        assertEquals(List.of(), PlannerPins.read(after));
    }
    @Test void legacyEmptyDeltasRetainAndForeignTargetsFail() {
        var before = trip("v-a", true);
        assertEquals(List.of("a"), PlannerPins.delta(before, null, null));
        assertThrows(IllegalArgumentException.class, () -> PlannerPins.delta(before, List.of("other"), null));
    }
}
