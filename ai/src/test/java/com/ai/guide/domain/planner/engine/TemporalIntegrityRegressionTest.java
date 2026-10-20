package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TemporalIntegrityRegressionTest {
    private final ItineraryTimeEngine engine = new ItineraryTimeEngine();
    private final PlanVerifier verifier = new PlanVerifier(new JsonTaxonomyAdapter());

    @Test void parsesHoursCompoundDecimalRangeAndHalfWithoutTreatingHoursAsMinutes() {
        assertEquals(120, engine.parseDurationMinutes("约 2 小时"));
        assertEquals(90, engine.parseDurationMinutes("1小时30分钟"));
        assertEquals(90, engine.parseDurationMinutes("1.5小时"));
        assertEquals(180, engine.parseDurationMinutes("约2-3小时"));
        assertEquals(90, engine.parseDurationMinutes("1小时半"));
        assertEquals(90, engine.parseDurationMinutes("一个半小时"));
        assertEquals(30, engine.parseDurationMinutes("半小时"));
        assertEquals(0, engine.parseDurationMinutes(null), "Missing duration must not create a synthetic visit length");
        assertEquals(0, engine.parseKnownDurationMinutes(null), "Unknown duration must remain unknown rather than inventing a visit length");
        assertNull(engine.parseExplicitTimeMinutes(null), "Unknown time must remain unknown to timeline calculations");
        assertThrows(IllegalArgumentException.class, () -> engine.parseTimeMinutes(null),
                "Compatibility callers must not silently turn a missing clock into noon");
    }

    @Test void rippleLeavesUnanchoredStopsWithoutInventedTimes() {
        var first = stop("a", "", "60分钟");
        var second = stop("b", "", "60分钟");
        engine.rippleRecalculateStopTimesFromExplicitFacts(new ArrayList<>(List.of(first, second)), 1);
        assertEquals("", second.get("time"));
    }

    @Test void explicitClockUsesValidTimeFirstAndDoesNotLetPlaceholderHideStartTime() {
        assertEquals("10:00", engine.explicitClock(Map.of("time", "10:00", "startTime", "11:00")));
        assertEquals("11:00", engine.explicitClock(Map.of("time", "待安排", "startTime", "11:00")));
        assertEquals("11:00", engine.explicitClock(Map.of("startTime", "11:00")));
    }

    @Test void slotClassificationUsesExternalTemporalWindows() {
        var taxonomy = new JsonTaxonomyAdapter();
        assertTrue(engine.isMorningSlot("08:30", taxonomy));
        assertFalse(engine.isMorningSlot("02:00", taxonomy));
        assertTrue(engine.isEveningSlot("17:30", taxonomy));
        assertTrue(engine.isEveningSlot("19:30", taxonomy));
        assertFalse(engine.isEveningSlot("15:00", taxonomy));
    }

    @Test void rippleIncludesFullVisitAndResolvedTransferAcrossMidnight() {
        var first = stop("a", "23:00", "2小时");
        var second = stop("b", "23:30", "90分钟");
        second.put("resolvedRouteCost", Map.of("durationMinutes", 37, "status", "ESTIMATED"));
        var stops = new ArrayList<>(List.of(first, second));
        engine.rippleRecalculateStopTimes(stops, 1);
        assertEquals("01:37(+1d)", second.get("time"));
        assertEquals(1537, engine.parseTimeMinutes(second.get("time").toString()));
    }

    @Test void verifierRejectsAdjacentOverlapIncludingTransferButAllowsExactBoundary() {
        var a = stop("a", "18:00", "90分钟");
        var b = stop("b", "19:00", "90分钟");
        b.put("resolvedRouteCost", Map.of("durationMinutes", 37, "status", "ESTIMATED"));
        assertTrue(has(List.of(day("2026-10-07", a, b)), new TravelConstraints(), "TIME_OVERLAP"));
        b.put("time", "20:07");
        assertFalse(has(List.of(day("2026-10-07", a, b)), new TravelConstraints(), "TIME_OVERLAP"));
        b.put("venueId", "a");
        b.put("time", "19:30");
        assertFalse(has(List.of(day("2026-10-07", a, b)), new TravelConstraints(), "TIME_OVERLAP"));
    }

    @Test void verifierRejectsTimedStopWithoutKnownVisitDuration() {
        assertTrue(has(List.of(day("2026-10-07", stop("a", "09:00", ""))),
                new TravelConstraints(), "VISIT_DURATION_UNKNOWN"));
    }

    @Test void verifierDoesNotInventTransferTimeWhenNoEstimatePolicyIsInstalled() {
        var withoutEstimate = new PlanVerifier(TaxonomyPort.empty());
        var first = stop("a", "09:00", "30分钟");
        var second = stop("b", "10:00", "30分钟");
        assertTrue(withoutEstimate.verify(List.of(day("2026-10-07", first, second)), new TravelConstraints())
                .violations().stream().anyMatch(v -> "ROUTE_DURATION_UNKNOWN".equals(v.type())));
    }

    @Test void arrivalAndDepartureCompareActualClockAndWholeVisit() {
        var c = TravelConstraints.builder().arrivalAt("2026-10-07 15:00").departureAt("22:30").build();
        assertTrue(has(List.of(day("2026-10-07", stop("a", "09:00", "60分钟"))), c, "ARRIVAL_WINDOW_CONFLICT"));
        assertTrue(has(List.of(day("2026-10-07", stop("a", "22:00", "60分钟"))), c, "DEPARTURE_DEADLINE_CONFLICT"));
        assertFalse(has(List.of(day("2026-10-07", stop("a", "21:30", "60分钟"))), c, "DEPARTURE_DEADLINE_CONFLICT"));
        c.setDepartureAt("上午");
        assertTrue(has(List.of(day("2026-10-07", stop("a", "22:30", "60分钟"))), c, "DEPARTURE_DEADLINE_CONFLICT"));
    }

    @Test void departureDateAndOffsetsReferToTheSameTimeline() {
        var c = TravelConstraints.builder().departureAt("2026-10-08 01:00").build();
        assertFalse(has(List.of(day("2026-10-07", stop("a", "23:30", "90分钟"))), c, "DEPARTURE_DEADLINE_CONFLICT"));
        assertTrue(has(List.of(day("2026-10-07", stop("a", "00:30(+1d)", "60分钟"))), c, "DEPARTURE_DEADLINE_CONFLICT"));
    }

    @Test void openingCoverageIncludesCloseTimeLunchGapOvernightAndWeeklyClosure() {
        assertTrue(OpeningHoursPolicy.isVisitOpen("09:00-17:00", "2026-10-07", "16:00", 60));
        assertFalse(OpeningHoursPolicy.isVisitOpen("09:00-17:00", "2026-10-07", "16:30", 90));
        assertFalse(OpeningHoursPolicy.isVisitOpen("09:00-12:00、13:00-17:00", "2026-10-07", "11:30", 120));
        assertTrue(OpeningHoursPolicy.isVisitOpen("18:00-02:00", "2026-10-07", "23:30", 90));
        assertFalse(OpeningHoursPolicy.isVisitOpen("18:00-02:00，周一闭馆", "2026-10-11", "23:30", 90));
        assertFalse(OpeningHoursPolicy.isVisitOpen("18:00-02:00，周一闭馆", "周日", "23:30", 90));
        assertTrue(OpeningHoursPolicy.isVisitOpen("", "2026-10-07", "16:30", 90));
        assertEquals("", OpeningHoursPolicy.evidence("", "建议上午09:00-11:00游览"));
    }

    @Test void verifierUsesWholeOpeningIntervalInsteadOfOnlyArrivalInstant() {
        var s = stop("museum", "16:30", "90分钟");
        s.put("openingHours", "09:00-17:00");
        assertTrue(has(List.of(day("2026-10-07", s)), new TravelConstraints(), "CLOSED_AT_SLOT_TIME"));
    }

    @Test void occupiedDayBoundaryChecksStartAndEndWhileAcceptingExactMidnightEnd() {
        assertTrue(has(List.of(day("2026-10-07", stop("a", "00:07(+1d)", "90分钟"))), new TravelConstraints(), "MIDNIGHT_OVERFLOW"));
        assertTrue(has(List.of(day("2026-10-07", stop("a", "23:37", "90分钟"))), new TravelConstraints(), "MIDNIGHT_OVERFLOW"));
        assertFalse(has(List.of(day("2026-10-07", stop("a", "22:30", "90分钟"))), new TravelConstraints(), "MIDNIGHT_OVERFLOW"));
    }

    private boolean has(List<Map<String,Object>> days, TravelConstraints c, String type) {
        return verifier.verify(days, c).violations().stream().anyMatch(v -> type.equals(v.type()));
    }
    static Map<String,Object> stop(String id, String time, String duration) {
        return new LinkedHashMap<>(Map.of("id", id, "venueId", id, "name", id, "time", time,
                "duration", duration, "district", "中心区"));
    }
    @SafeVarargs static Map<String,Object> day(String date, Map<String,Object>... stops) {
        return new LinkedHashMap<>(Map.of("day", 1, "date", date, "stops", new ArrayList<>(List.of(stops))));
    }
}
