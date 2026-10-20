package com.ai.guide.domain.planner.service;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.RouteGatewayPort;
import com.ai.guide.domain.planner.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlannerMutationPolicyTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ItineraryBuilderPort catalog;
    private PlannerSessionRepositoryPort repository;
    private RouteGatewayPort gateway;
    private PlannerService service;
    private PlannerSessionRepositoryPort.StoredSession session;
    private String originalSnapshot;
    private final Attraction local = attraction("local", "甲城", "09:00-23:00");
    private final Attraction foreign = attraction("foreign", "乙城", "09:00-23:00");

    @BeforeEach void setup() {
        UserContext.set("mutation-owner", "USER");
        catalog = mock(ItineraryBuilderPort.class, CALLS_REAL_METHODS);
        repository = mock(PlannerSessionRepositoryPort.class);
        gateway = mock(RouteGatewayPort.class);
        for (Attraction item : List.of(local, foreign)) when(catalog.attraction(item.getId())).thenReturn(item);
        when(catalog.attractionServiceList()).thenReturn(List.of(foreign, local));
        when(catalog.createStop(any(), anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            Attraction item = call.getArgument(0);
            var stop = stop(call.getArgument(1), item.getId(), call.getArgument(2));
            stop.put("duration", call.getArgument(3));
            stop.put("openingHours", item.getOpeningHours());
            return stop;
        });
        useSession(TravelConstraints.builder().destination("甲城").build(), trip(stop("original", "old", "10:00")));
        service = new PlannerService(null, catalog, repository, null, mapper, gateway, null, null);
    }
    @AfterEach void clear() { UserContext.clear(); }

    @Test void manualAddRejectsForeignCatalogEntryBeforeProviderAndWrite() {
        var request = command("add", "foreign");
        var result = service.mutateStops("session", request);
        assertEquals(400, result.status());
        assertEquals(List.of("DESTINATION_CITY_CONFLICT"), result.body().get("reasonCodes"));
        assertRejectedWithoutChanges();
        verifyNoInteractions(gateway);
    }
    @Test void manualReplaceRejectsForeignCatalogEntry() {
        assertEquals(400, service.mutateStops("session", command("replace", "foreign")).status());
        assertRejectedWithoutChanges();
    }
    @Test void requestedReplanRejectsForeignCatalogEntry() {
        when(catalog.attraction("old")).thenReturn(attraction("old", "甲城", "09:00-23:00"));
        var request = command("replace", "foreign"); request.setCandidateVenueId("foreign");
        assertEquals(400, service.replan("session", request).status());
        assertRejectedWithoutChanges();
    }
    @Test void automaticReplacementFiltersForeignCandidatesBeforeRanking() {
        when(catalog.attraction("old")).thenReturn(attraction("old", "甲城", "09:00-23:00"));
        var result = service.replan("session", command("replace", ""));
        assertEquals(200, result.status());
        assertEquals("local", result.body().get("replacementVenueId"));
    }
    @Test void avoidConstraintRejectsManualCandidate() {
        useSession(TravelConstraints.builder().destination("甲城").avoid(List.of("local")).build(), session.trip());
        var result = service.mutateStops("session", command("add", "local"));
        assertEquals(400, result.status());
        assertEquals(List.of("AVOIDED_PLACE"), result.body().get("reasonCodes"));
        assertRejectedWithoutChanges();
    }
    @Test void replacingIntoClosedVisitIntervalDoesNotPersist() {
        local.setOpeningHours("09:00-10:30");
        var result = service.mutateStops("session", command("replace", "local"));
        assertEquals(400, result.status());
        assertTrue(result.body().get("reasonCodes").toString().contains("CLOSED_AT_SLOT_TIME"));
        assertRejectedWithoutChanges();
    }
    @Test void newDepartureViolationRejectsWithoutVersionChange() {
        useSession(TravelConstraints.builder().destination("甲城").departureAt("10:30").build(), trip(stop("original", "old", "10:00")));
        // Original unresolved duration is not equivalent to the new candidate's verified 60 minute visit.
        ((Map<String, Object>) ((List<?>) ((Map<?, ?>) ((List<?>) session.trip().get("days")).get(0)).get("stops")).get(0)).put("duration", "15分钟");
        doAnswer(call -> {
            var stop = stop(call.getArgument(1), "local", "10:00"); stop.put("duration", "60分钟"); return stop;
        }).when(catalog).createStop(any(), anyString(), anyString(), anyString(), anyString(), any());
        var result = service.mutateStops("session", command("replace", "local"));
        assertEquals(400, result.status());
        assertTrue(result.body().get("reasonCodes").toString().contains("DEPARTURE_DEADLINE_CONFLICT"));
        verify(repository, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }
    @Test void harmlessDeletionIsAllowedDespiteExistingViolation() {
        var bad = stop("existing-bad", "old", "23:30"); bad.put("duration", "90分钟");
        useSession(TravelConstraints.builder().destination("甲城").build(), trip(bad, stop("remove-me", "local", "待安排")));
        var request = command("delete", ""); request.setStopId("remove-me");
        assertEquals(200, service.mutateStops("session", request).status());
        verify(repository).update(any(), anyMap(), eq(1), anyString(), anyString(), anyString(), anyList());
    }
    @Test void appendedStopUsesKnownRouteAndPreviousEndRatherThanFixedMealClock() {
        useSession(TravelConstraints.builder().destination("甲城").departureAt("14:00").build(), session.trip());
        provideIncomingRoute(15);
        var result = service.mutateStops("session", command("add", "local"));
        assertEquals(200, result.status());
        var stops = tripStops((Map<String, Object>) result.body().get("trip"));
        assertEquals("11:15", stops.get(1).get("time"));
        assertEquals("10:00", tripStops(session.trip()).get(0).get("time"));
    }
    @Test void unresolvedAppendCannotBypassExplicitDepartureBoundary() {
        useSession(TravelConstraints.builder().destination("甲城").departureAt("14:00").build(), session.trip());
        var result = service.mutateStops("session", command("add", "local"));
        assertEquals(400, result.status());
        assertTrue(result.body().get("reasonCodes").toString().contains("TIMING_NOT_RESOLVED"));
        assertRejectedWithoutChanges();
    }
    @Test void appendedStopAlsoUsesStartTimeOnlyAnchor() {
        var anchor = stop("original", "old", "10:00"); anchor.remove("time"); anchor.put("startTime", "10:00");
        useSession(TravelConstraints.builder().destination("甲城").departureAt("14:00").build(), trip(anchor));
        provideIncomingRoute(15);
        var result = service.mutateStops("session", command("add", "local"));
        assertEquals(200, result.status());
        assertEquals("11:15", tripStops((Map<String, Object>) result.body().get("trip")).get(1).get("time"));
    }
    @Test void firstStopReplacementRetainsStartTimeOnlyClock() {
        var anchor = stop("original", "old", "10:00"); anchor.remove("time"); anchor.put("startTime", "10:00");
        useSession(TravelConstraints.builder().destination("甲城").departureAt("12:00").build(), trip(anchor));
        var result = service.mutateStops("session", command("replace", "local"));
        assertEquals(200, result.status());
        assertEquals("10:00", tripStops((Map<String, Object>) result.body().get("trip")).get(0).get("time"));
    }
    @Test void firstStopReplanRetainsValidStartTimeBehindPlaceholder() {
        var anchor = stop("original", "old", "待安排"); anchor.put("startTime", "10:00");
        useSession(TravelConstraints.builder().destination("甲城").departureAt("12:00").build(), trip(anchor));
        when(catalog.attraction("old")).thenReturn(attraction("old", "甲城", "09:00-23:00"));
        var request = command("replace", ""); request.setCandidateVenueId("local");
        var result = service.replan("session", request);
        assertEquals(200, result.status());
        assertEquals("10:00", tripStops((Map<String, Object>) result.body().get("trip")).get(0).get("time"));
    }
    @Test void calculatedAppendPastDepartureIsRejectedWithoutRepairDeletingOtherStops() {
        useSession(TravelConstraints.builder().destination("甲城").departureAt("11:30").build(), session.trip());
        provideIncomingRoute(15);
        var result = service.mutateStops("session", command("add", "local"));
        assertEquals(400, result.status());
        assertTrue(result.body().get("reasonCodes").toString().contains("DEPARTURE_DEADLINE_CONFLICT"));
        assertRejectedWithoutChanges();
    }
    @Test void explicitMultiCityDestinationAllowsBothCities() {
        useSession(TravelConstraints.builder().destination("甲城市、乙城市").build(), session.trip());
        assertEquals(200, service.mutateStops("session", command("add", "foreign")).status());
    }
    @Test void incidentalForeignStopDoesNotGrantCityScope() {
        useSession(TravelConstraints.builder().destination("甲城").build(), trip(stop("original", "foreign", "10:00")));
        var request = command("add", "foreign"); request.setAllowRepeat(true);
        assertEquals(400, service.mutateStops("session", request).status());
        assertRejectedWithoutChanges();
    }
    @Test void unknownOperationIsRejectedInsteadOfAdding() {
        assertEquals(400, service.mutateStops("session", command("typo", "local")).status());
        assertRejectedWithoutChanges();
    }
    @Test void staleVersionRejectsBeforeHydration() {
        var request = command("add", "local"); request.setExpectedVersion(0);
        assertEquals(409, service.mutateStops("session", request).status());
        verifyNoInteractions(gateway);
        assertRejectedWithoutChanges();
    }
    @Test void sharedPolicyMarksForeignAiOptionAsNonForceable() {
        var policy = new PlannerMutationPolicy(catalog, new PlanVerifier());
        var result = policy.verify(session.trip(), trip(stop("added-ai", "foreign", "12:00")),
                TravelConstraints.builder().destination("甲城").build());
        assertFalse(result.feasible());
        assertTrue(result.violations().stream().anyMatch(v -> v.type().equals("DESTINATION_CITY_CONFLICT") && PlannerMutationPolicy.nonForceable(v)));
    }
    @Test void replacementDelaysOnlyConflictingChainAndReportsActualChangedSegments() {
        var anchor = stop("anchor", "anchor-venue", "09:30");
        var target = stop("original", "old", "10:00");
        var next = stop("next", "next-venue", "11:40");
        useSession(TravelConstraints.builder().destination("甲城").build(), trip(anchor, target, next));
        provideAllIncomingRoutes(15);
        var result = service.mutateStops("session", command("replace", "local"));
        assertEquals(200, result.status());
        var stops = tripStops((Map<String, Object>) result.body().get("trip"));
        assertEquals("09:30", stops.get(0).get("time"));
        assertEquals("10:45", stops.get(1).get("time"));
        assertEquals("12:00", stops.get(2).get("time"));
        assertEquals(List.of("original", "next"), result.body().get("changedSegments"));
        assertEquals(originalSnapshot, mapper.valueToTree(session.trip()).toString());
    }
    @Test void replacementPreservesFreeGapsAndDoesNotMoveOtherDays() {
        var planned = trip(stop("anchor", "anchor-venue", "09:30"), stop("original", "old", "10:00"), stop("next", "next-venue", "13:00"));
        @SuppressWarnings("unchecked") var days = (List<Map<String, Object>>) planned.get("days");
        var untouchedDay = new LinkedHashMap<String, Object>(Map.of("day", 2, "stops", new ArrayList<>(List.of(stop("other-day", "other", "10:00")))));
        days.add(untouchedDay);
        useSession(TravelConstraints.builder().destination("甲城").build(), planned);
        provideAllIncomingRoutes(15);
        var result = service.mutateStops("session", command("replace", "local"));
        assertEquals(200, result.status());
        var after = (Map<String, Object>) result.body().get("trip");
        assertEquals("13:00", tripStops(after).get(2).get("time"));
        assertEquals(untouchedDay, ((List<?>) after.get("days")).get(1));
        assertEquals(List.of("original"), result.body().get("changedSegments"));
    }
    @Test void delayedReplacementCannotCrossExplicitDepartureBoundary() {
        useSession(TravelConstraints.builder().destination("甲城").departureAt("11:30").build(),
                trip(stop("anchor", "anchor-venue", "09:30"), stop("original", "old", "10:00")));
        provideAllIncomingRoutes(15);
        var result = service.mutateStops("session", command("replace", "local"));
        assertEquals(400, result.status());
        assertTrue(result.body().get("reasonCodes").toString().contains("DEPARTURE_DEADLINE_CONFLICT"));
        assertRejectedWithoutChanges();
    }
    @Test void configuredUnknownReserveIsSharedAndExplicitlyMarkedAsEstimated() {
        var policy = new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter();
        service.configureMutationTiming(policy);
        int reserved = policy.getPolicy().unknownTravelDurationMinutes();
        var next = stop("next", "next-venue", "10:30");
        next.put("resolvedRouteCost", Map.of("durationMinutes", 5));
        useSession(TravelConstraints.builder().destination("甲城").build(), trip(stop("original", "old", "10:00"), next));
        var result = service.mutateStops("session", command("replace", "local"));
        assertEquals(200, result.status());
        var after = tripStops((Map<String, Object>) result.body().get("trip")).get(1);
        assertFalse(after.containsKey("resolvedRouteCost"), "Old endpoint's route must not survive replacement");
        assertEquals(new com.ai.guide.domain.planner.engine.ItineraryTimeEngine().formatTime(11 * 60 + reserved), after.get("time"));
        var estimate = (Map<?, ?>) after.get("estimatedRouteCost");
        assertEquals(reserved, estimate.get("durationMinutes"));
        assertEquals("ESTIMATED", estimate.get("routeDataStatus"));
        assertEquals("CONFIGURED_UNKNOWN_TRANSFER", estimate.get("source"));
    }
    @Test void noPolicyDoesNotReuseOldEndpointRouteOrInventTransferTime() {
        var next = stop("next", "next-venue", "12:00");
        next.put("resolvedRouteCost", Map.of("durationMinutes", 5));
        useSession(TravelConstraints.builder().destination("甲城").build(), trip(stop("original", "old", "10:00"), next));
        var result = service.mutateStops("session", command("replace", "local"));
        assertEquals(400, result.status());
        assertTrue(result.body().get("reasonCodes").toString().contains("ROUTE_DURATION_UNKNOWN"));
        assertRejectedWithoutChanges();
    }
    @Test void deletingMiddleStopReplacesItsSuccessorsOldEdgeAndReportsActualDelay() {
        var policy = new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter();
        service.configureMutationTiming(policy);
        var next = stop("next", "next-venue", "10:10");
        next.put("resolvedRouteCost", Map.of("durationMinutes", 5));
        next.put("estimatedRouteCost", Map.of("durationMinutes", 3));
        useSession(TravelConstraints.builder().destination("甲城").build(),
                trip(stop("anchor", "anchor-venue", "09:00"), stop("original", "old", "09:30"), next));
        var request = command("delete", ""); request.setStopId("original");
        var result = service.mutateStops("session", request);
        assertEquals(200, result.status());
        var successor = tripStops((Map<String, Object>) result.body().get("trip")).get(1);
        assertFalse(successor.containsKey("resolvedRouteCost"));
        var reserve = (Map<?, ?>) successor.get("estimatedRouteCost");
        assertEquals("CONFIGURED_UNKNOWN_TRANSFER", reserve.get("source"));
        assertEquals(policy.getPolicy().unknownTravelDurationMinutes(), reserve.get("durationMinutes"));
        assertEquals(new com.ai.guide.domain.planner.engine.ItineraryTimeEngine().formatTime(10 * 60 + policy.getPolicy().unknownTravelDurationMinutes()), successor.get("time"));
        assertEquals(List.of("original", "next"), result.body().get("changedSegments"));
        assertEquals(originalSnapshot, mapper.valueToTree(session.trip()).toString());
    }
    @Test void deletingFirstStopClearsAllIncomingFactsWithoutInventingANewFirstEdge() {
        service.configureMutationTiming(new com.ai.guide.infrastructure.planner.taxonomy.JsonTaxonomyAdapter());
        var next = stop("next", "next-venue", "12:00");
        next.put("resolvedRouteCost", Map.of("durationMinutes", 5));
        next.put("estimatedRouteCost", Map.of("durationMinutes", 3));
        next.put("routeFromPrevious", Map.of("selected", Map.of("durationSeconds", 300)));
        next.put("routeDataStatus", "ESTIMATED");
        next.put("mapContext", Map.of("routeFromPrevious", Map.of("durationSeconds", 300), "coordinates", List.of(1, 2)));
        useSession(TravelConstraints.builder().destination("甲城").build(), trip(stop("original", "old", "10:00"), next));
        var request = command("delete", ""); request.setStopId("original");
        var result = service.mutateStops("session", request);
        assertEquals(200, result.status());
        var first = tripStops((Map<String, Object>) result.body().get("trip")).get(0);
        for (String field : List.of("resolvedRouteCost", "estimatedRouteCost", "routeFromPrevious", "routeDataStatus")) assertFalse(first.containsKey(field), field);
        assertFalse(((Map<?, ?>) first.get("mapContext")).containsKey("routeFromPrevious"));
        assertEquals("12:00", first.get("time"));
        assertEquals(List.of("original"), result.body().get("changedSegments"));
        assertEquals(originalSnapshot, mapper.valueToTree(session.trip()).toString());
    }

    private void useSession(TravelConstraints constraints, Map<String, Object> trip) {
        session = new PlannerSessionRepositoryPort.StoredSession("session", "USER", "mutation-owner", "", "", "", constraints.asMap(),
                AppliedPreferencesSnapshot.empty(), trip, 1, 1, "DRAFT", 1, 1);
        originalSnapshot = mapper.valueToTree(trip).toString();
        when(repository.find(eq("session"), eq("USER"), eq("mutation-owner"), anyString())).thenReturn(session);
        when(repository.update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList())).thenAnswer(call ->
                PlannerSessionRepositoryPort.MutationResult.updated(new PlannerSessionRepositoryPort.StoredSession("session", "USER", "mutation-owner", "", "", "",
                        constraints.asMap(), AppliedPreferencesSnapshot.empty(), call.getArgument(1), 2, 2, "DRAFT", 1, 2)));
    }
    private void provideIncomingRoute(int minutes) {
        doAnswer(call -> {
            var stops = tripStops(call.getArgument(0));
            stops.get(stops.size() - 1).put("resolvedRouteCost", Map.of("durationMinutes", minutes));
            return null;
        }).when(gateway).hydrate(anyMap());
    }
    private void provideAllIncomingRoutes(int minutes) {
        doAnswer(call -> {
            var stops = tripStops(call.getArgument(0));
            for (int i = 1; i < stops.size(); i++) stops.get(i).put("resolvedRouteCost", Map.of("durationMinutes", minutes));
            return null;
        }).when(gateway).hydrate(anyMap());
    }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> tripStops(Map<String, Object> trip) {
        return (List<Map<String, Object>>) ((Map<?, ?>) ((List<?>) trip.get("days")).get(0)).get("stops");
    }
    private void assertRejectedWithoutChanges() {
        assertEquals(1, session.currentVersion()); assertEquals(1, session.trip().get("version"));
        assertEquals(originalSnapshot, mapper.valueToTree(session.trip()).toString());
        verify(repository, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
    }
    private PlannerMutationRequest command(String operation, String venue) {
        var request = new PlannerMutationRequest(); request.setOperation(operation); request.setAttractionId(venue);
        request.setTargetStopId("original"); request.setDay(1); request.setExpectedVersion(1); return request;
    }
    private static Attraction attraction(String id, String city, String opening) {
        return Attraction.builder().id(id).name(id).city(city).duration("60分钟").openingHours(opening).tags(List.of()).build();
    }
    private static Map<String, Object> stop(String id, String venue, String time) {
        return new LinkedHashMap<>(Map.of("id", id, "venueId", venue, "name", venue, "time", time, "duration", "60分钟"));
    }
    @SafeVarargs private static Map<String, Object> trip(Map<String, Object>... stops) {
        return new LinkedHashMap<>(Map.of("city", "甲城", "version", 1, "days", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("day", 1, "date", "2026-10-16", "stops", new ArrayList<>(Arrays.asList(stops))))))));
    }
}
