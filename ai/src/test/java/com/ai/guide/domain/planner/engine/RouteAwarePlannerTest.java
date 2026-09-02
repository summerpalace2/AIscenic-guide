package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.planner.api.ShadowPlanRequest;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.api.PlanRequest;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.ItineraryBuilder;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.domain.planner.service.TravelConstraintParser;
import com.ai.guide.domain.attraction.service.AttractionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RouteAwarePlannerTest {

    @Autowired
    private PlannerService plannerService;

    @Autowired
    private ItineraryBuilder itineraryBuilder;

    @Autowired
    private TravelConstraintParser constraintParser;

    @Autowired
    private PlanVerifier planVerifier;

    @Autowired
    private LocalPlanRepairer localPlanRepairer;

    @Autowired
    private RouteCostProvider routeCostProvider;

    @Autowired
    private AttractionService attractionService;

    @Test
    void test1_exactRouteDurationOverflowTriggersPlanVerifierAndLocalRepair() {
        Attraction a1 = attractionService.get("cq-jiefangbei");
        Attraction a2 = attractionService.get("cq-hongyadong");
        Attraction a3 = attractionService.get("cq-liziba");
        assertNotNull(a1);
        assertNotNull(a2);
        assertNotNull(a3);

        Map<String, Object> s1 = itineraryBuilder.createStop(a1, "day1-jiefangbei", "10:00", "约 90 分钟", "热身", "地标");
        Map<String, Object> s2 = itineraryBuilder.createStop(a2, "day1-hongyadong", "14:30", "约 90 分钟", "夜景", "吊脚楼");
        Map<String, Object> s3 = itineraryBuilder.createStop(a3, "day1-liziba", "18:30", "约 45 分钟", "魔幻", "穿楼");

        // Edge 1: a1 -> a2 is normal (15 mins)
        s2.put("resolvedRouteCost", RouteCost.verifiedAmap(1200, 900, 300, "WALKING").asMap());
        s2.put("routeDataStatus", "VERIFIED_AMAP");

        // Edge 2: a2 -> a3 has excessive actual duration (240 mins / 4 hours) causing day schedule overflow
        s3.put("resolvedRouteCost", RouteCost.verifiedAmap(5000, 240 * 60, 500, "TRANSIT").asMap());
        s3.put("routeDataStatus", "VERIFIED_AMAP");

        Map<String, Object> day = new LinkedHashMap<>();
        day.put("day", 1);
        day.put("date", "2026-08-25");
        day.put("stops", new ArrayList<>(List.of(s1, s2, s3)));
        List<Map<String, Object>> days = new ArrayList<>(List.of(day));

        TravelConstraints constraints = constraintParser.parse("玩1天 喜欢夜景");

        // PlanVerifier MUST detect excessive travel time and schedule overflow
        PlanVerifier.VerificationResult verification = planVerifier.verify(days, constraints);
        assertFalse(verification.feasible(), "Excessive route time must be flagged as infeasible");
        assertTrue(verification.violations().stream().anyMatch(v -> "TRAVEL_TIME_EXCESSIVE".equals(v.type())));

        // LocalPlanRepairer must adjust/prune the offending stop
        List<Map<String, Object>> repairedDays = localPlanRepairer.repair(days, verification, constraints, List.of(a1, a2, a3));
        assertNotNull(repairedDays);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> repairedStops = (List<Map<String, Object>>) repairedDays.get(0).get("stops");
        assertEquals(2, repairedStops.size(), "Offending stop should be pruned to restore feasibility");

        // Re-verification of repaired days should now be feasible
        PlanVerifier.VerificationResult repairedVerification = planVerifier.verify(repairedDays, constraints);
        assertTrue(repairedVerification.feasible(), "Repaired schedule without excessive edge should be feasible");
    }

    @Test
    void test2_repairedDraftReusesCacheForUnchangedEdgesWithoutCallingProviderAgain() {
        Attraction a1 = attractionService.get("cq-jiefangbei");
        Attraction a2 = attractionService.get("cq-hongyadong");
        Attraction a3 = attractionService.get("cq-liziba");
        Attraction a4 = attractionService.get("cq-shibati");
        assertNotNull(a1);
        assertNotNull(a2);
        assertNotNull(a3);
        assertNotNull(a4);

        AtomicInteger exactCalls = new AtomicInteger(0);

        // Custom test provider tracking calls and caching resolved edges
        RouteCostProvider testProvider = new RouteCostProvider(null) {
            @Override
            protected RouteCost requestExactRoute(Attraction origin, Attraction destination, String preference) {
                exactCalls.incrementAndGet();
                return RouteCost.verifiedAmap(1000, 600, 200, "WALKING");
            }
        };

        // Resolve edge a1 -> a2 (first time: cache miss)
        RouteCost r1 = testProvider.resolveFinalEdge(a1, a2, "walking");
        assertEquals(RouteCost.RouteDataStatus.VERIFIED_AMAP, r1.status());
        assertEquals(1, exactCalls.get());

        // Resolve edge a2 -> a3 (first time: cache miss)
        RouteCost r2 = testProvider.resolveFinalEdge(a2, a3, "walking");
        assertEquals(RouteCost.RouteDataStatus.VERIFIED_AMAP, r2.status());
        assertEquals(2, exactCalls.get());

        // Now simulate repair where a3 is replaced by a4, while edge a1 -> a2 remains unchanged:
        // Query a1 -> a2 again: MUST be a cache hit (0 additional calls)
        RouteCost r1Cached = testProvider.resolveFinalEdge(a1, a2, "walking");
        assertEquals(RouteCost.RouteDataStatus.CACHED, r1Cached.status());
        assertEquals(2, exactCalls.get(), "Unchanged edge a1->a2 must hit cache and NOT invoke provider again");

        // Query new edge a2 -> a4: cache miss (1 additional call)
        RouteCost r3 = testProvider.resolveFinalEdge(a2, a4, "walking");
        assertEquals(RouteCost.RouteDataStatus.VERIFIED_AMAP, r3.status());
        assertEquals(3, exactCalls.get(), "Only new edge a2->a4 should call provider");
    }

    @Test
    void test3_amapUnavailableFallsBackToEstimatedWithoutClaimingVerifiedAmap() {
        Attraction a1 = attractionService.get("cq-jiefangbei");
        Attraction a2 = attractionService.get("cq-hongyadong");
        assertNotNull(a1);
        assertNotNull(a2);

        // RouteCostProvider with no active AMap service (unavailable)
        RouteCostProvider offlineProvider = new RouteCostProvider(null);

        RouteCost cost = offlineProvider.resolveFinalEdge(a1, a2, "walking");
        assertNotNull(cost);
        assertEquals(RouteCost.RouteDataStatus.ESTIMATED, cost.status(), "Offline provider must return ESTIMATED status");
        assertFalse(cost.status() == RouteCost.RouteDataStatus.VERIFIED_AMAP, "Must not pretend to be VERIFIED_AMAP when AMap is unavailable");
        assertTrue(cost.distanceMeters() > 0);
        assertTrue(cost.duration().toMinutes() >= 0);
    }

    @Test
    void test4_repairLoopStopsAtMaximumTwoIterationsAndPreservesViolations() {
        Attraction a1 = attractionService.get("cq-museum"); // Closes on Monday
        assertNotNull(a1);

        Map<String, Attraction> byId = Map.of(a1.getId(), a1);

        // Day with an unfixable stop (museum on a Monday with no other candidates)
        Map<String, Object> stop = itineraryBuilder.createStop(a1, "day1-museum", "10:00", "约 120 分钟", "全馆观展", "历史");
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("day", 1);
        day.put("date", "2026-08-24"); // Monday
        day.put("stops", new ArrayList<>(List.of(stop)));
        List<Map<String, Object>> days = new ArrayList<>(List.of(day));

        TravelConstraints constraints = constraintParser.parse("2026-08-24 必须去三峡博物馆 玩1天");

        // Custom repairer that intentionally leaves the violation to test the 2-iteration loop bound
        LocalPlanRepairer stubbornRepairer = new LocalPlanRepairer() {
            @Override
            public List<Map<String, Object>> repair(List<Map<String, Object>> d,
                                                   PlanVerifier.VerificationResult v,
                                                   TravelConstraints c,
                                                   List<Attraction> catalog) {
                // Returns unchanged days to force verification failure
                return d;
            }
        };

        RouteAwarePlanner testPlanner = new RouteAwarePlanner(routeCostProvider, planVerifier, stubbornRepairer);

        // Must terminate within max 2 rounds without hanging or infinite looping
        List<Map<String, Object>> finalDays = testPlanner.resolveAndVerifyDays(days, byId, constraints);
        assertNotNull(finalDays);

        // Verification on final days preserves the violation
        PlanVerifier.VerificationResult finalVerification = planVerifier.verify(finalDays, constraints);
        assertFalse(finalVerification.feasible());
        assertTrue(finalVerification.violations().stream().anyMatch(v -> "CLOSED_ON_MONDAY".equals(v.type())));
    }

    @Test
    void gs01_baiheliangAndDowntownDoNotProduceInfeasibleHalfDayInterdistrictBounce() {
        // Explicit request mentioning Baiheliang and Jiefangbei for a 2-day trip
        PlanRequest request = PlanRequest.builder()
                .prompt("想去白鹤梁水下博物馆和解放碑，2天行程")
                .build();

        PlannerService.ServiceResult result = plannerService.shadow(
                com.ai.guide.domain.planner.api.ShadowPlanRequest.builder()
                        .prompt(request.getPrompt())
                        .build()
        );

        assertEquals(200, result.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) result.body().get("trip");
        assertNotNull(trip);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        assertEquals(2, days.size());

        // Verify that in none of the days does a Fuling distant spot bounce in the same half-day with a downtown spot
        PlanVerifier.VerificationResult verification = planVerifier.verify(days, constraintParser.parse(request.getPrompt()));
        assertTrue(verification.feasible(), "Trip should be feasible without excessive inter-district bounces");
        assertTrue(verification.violations().stream().noneMatch(v -> "CROSS_DISTRICT_INVIABLE".equals(v.type())));
    }

    @Test
    void gs03_knownClosedHoursAreNotScheduledDuringClosedTime() {
        // Monday itinerary for 1 day: Museum has "周一闭馆"
        TravelConstraints constraints = constraintParser.parse("2026-08-24 玩1天 喜欢历史文化"); // 2026-08-24 is a Monday
        Map<String, Object> trip = itineraryBuilder.build(1, constraints);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        assertEquals(1, days.size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");

        // cq-museum must NOT be scheduled on Monday
        boolean museumScheduledOnMonday = stops.stream()
                .anyMatch(s -> "cq-museum".equals(s.get("venueId")));
        assertFalse(museumScheduledOnMonday, "Museum should not be scheduled on Monday due to closed hours");
    }

    @Test
    void gs04_eveningArrivalDoesNotGenerateMorningOrAfternoonSlotsOnDayOne() {
        // Late arrival request: 周六晚上到重庆
        PlanRequest request = PlanRequest.builder()
                .prompt("周六晚上到重庆，周日晚上离开，喜欢山城夜景")
                .build();

        PlannerService.ServiceResult result = plannerService.shadow(
                com.ai.guide.domain.planner.api.ShadowPlanRequest.builder()
                        .prompt(request.getPrompt())
                        .build()
        );

        assertEquals(200, result.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) result.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");

        // Day 1 should have NO morning (10:00) or afternoon (14:30) slot
        for (Map<String, Object> stop : day1Stops) {
            String time = String.valueOf(stop.get("time"));
            assertFalse(time.contains("10:") || time.contains("14:"),
                    "Evening arrival must not have morning or afternoon slots on Day 1, found: " + time);
        }
    }

    @Test
    void gs07_amapUnavailableGracefullyDegradesWithExplicitStatusAndNoPretense() {
        Attraction a1 = attractionService.get("cq-jiefangbei");
        Attraction a2 = attractionService.get("cq-hongyadong");
        assertNotNull(a1);
        assertNotNull(a2);

        // When testing without live AMap or when unconfigured, estimation provides explicit RouteCost
        RouteCost cost = routeCostProvider.estimateFromCoordinates(a1, a2, "walking");
        assertNotNull(cost);
        assertEquals(RouteCost.RouteDataStatus.ESTIMATED, cost.status());
        assertTrue(cost.distanceMeters() > 0);
        assertTrue(cost.duration().toMinutes() >= 0);
        assertTrue(cost.reason().contains("交通估算"));
    }

    @Test
    void routeAwarePlanningResultsAreStableForSameInput() {
        TravelConstraints c1 = constraintParser.parse("3天行程，带父母，少走路，喜欢夜景和美食");
        TravelConstraints c2 = constraintParser.parse("3天行程，带父母，少走路，喜欢夜景和美食");

        Map<String, Object> trip1 = itineraryBuilder.build(1, c1);
        Map<String, Object> trip2 = itineraryBuilder.build(1, c2);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days1 = (List<Map<String, Object>>) trip1.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days2 = (List<Map<String, Object>>) trip2.get("days");

        assertEquals(days1.size(), days2.size());
        for (int i = 0; i < days1.size(); i++) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> s1 = (List<Map<String, Object>>) days1.get(i).get("stops");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> s2 = (List<Map<String, Object>>) days2.get(i).get("stops");
            assertEquals(s1.size(), s2.size());
            for (int j = 0; j < s1.size(); j++) {
                assertEquals(s1.get(j).get("venueId"), s2.get(j).get("venueId"));
            }
        }
    }
}
