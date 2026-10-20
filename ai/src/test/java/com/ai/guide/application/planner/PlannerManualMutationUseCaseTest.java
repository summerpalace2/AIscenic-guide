package com.ai.guide.application.planner;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.RouteGatewayPort;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerMutationRequest;
import com.ai.guide.domain.planner.service.ItineraryBuilderPort;
import com.ai.guide.domain.planner.service.PlannerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlannerManualMutationUseCaseTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PlannerSessionRepositoryPort domainRepository = mock(PlannerSessionRepositoryPort.class);
    private final PlannerSessionRepositoryPort applicationRepository = mock(PlannerSessionRepositoryPort.class);
    private final ItineraryBuilderPort catalog = mock(ItineraryBuilderPort.class, CALLS_REAL_METHODS);
    private final RouteGatewayPort gateway = mock(RouteGatewayPort.class);
    private PlannerApplicationService application;
    private PlannerSessionRepositoryPort.StoredSession session;
    private String before;

    @BeforeEach void setup() {
        UserContext.set("other-thread-user", "USER");
        var stop = new LinkedHashMap<String, Object>(Map.of("id", "original", "venueId", "old", "name", "old", "time", "10:00", "duration", "60分钟"));
        var trip = new LinkedHashMap<String, Object>(Map.of("city", "测试城", "version", 1, "days", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("day", 1, "stops", new ArrayList<>(List.of(stop))))))));
        session = stored("session", "USER", "actual-owner", trip, 1);
        before = mapper.valueToTree(trip).toString();
        when(applicationRepository.find(eq("session"), eq("USER"), eq("actual-owner"), anyString())).thenReturn(session);
        when(applicationRepository.update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList())).thenAnswer(call ->
                PlannerSessionRepositoryPort.MutationResult.updated(stored("session", "USER", "actual-owner", call.getArgument(1), 2)));
        var candidate = Attraction.builder().id("local").city("测试城").name("local").duration("60分钟").build();
        when(catalog.attraction("local")).thenReturn(candidate);
        when(catalog.createStop(any(), anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(call ->
                new LinkedHashMap<String, Object>(Map.of("id", "original", "venueId", "local", "name", "local", "time", "10:00", "duration", "60分钟")));
        var domain = new PlannerService(null, catalog, domainRepository, null, mapper, gateway, null, null);
        application = new PlannerApplicationService(domain, null, applicationRepository, null, null, null, null);
    }
    @AfterEach void clear() { UserContext.clear(); }

    @Test void explicitActorOwnsManualReplacementAndOnlyApplicationPerformsCas() {
        var request = command("replace"); request.setAttractionId("local");
        var result = application.mutateStops(new PlanningActor("actual-owner", false), "session", request);
        assertEquals(200, result.status());
        verify(applicationRepository).update(eq(session), anyMap(), eq(1), eq("STOP_MUTATION"), anyString(), anyString(), eq(List.of("original")));
        verifyNoInteractions(domainRepository);
        assertEquals(before, mapper.valueToTree(session.trip()).toString());
    }
    @Test void explicitActorAlsoOwnsReplanRatherThanThreadLocalUser() {
        var request = command("replace"); request.setCandidateVenueId("local");
        when(catalog.attraction("old")).thenReturn(Attraction.builder().id("old").city("测试城").name("old").build());
        var result = application.replan(new PlanningActor("actual-owner", false), "session", request);
        assertEquals(200, result.status());
        verify(applicationRepository).update(eq(session), anyMap(), eq(1), eq("REPLAN"), anyString(), anyString(), anyList());
        verifyNoInteractions(domainRepository);
    }
    @Test void otherOwnerCannotPrepareOrWriteTheTargetSession() {
        assertEquals(404, application.mutateStops(new PlanningActor("other-owner", false), "session", command("delete")).status());
        verify(applicationRepository, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
        verifyNoInteractions(gateway, domainRepository);
    }
    @Test void staleVersionStopsBeforeProviderAndCas() {
        var request = command("delete"); request.setExpectedVersion(0);
        assertEquals(409, application.mutateStops(new PlanningActor("actual-owner", false), "session", request).status());
        verify(applicationRepository, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
        verifyNoInteractions(gateway, domainRepository);
    }
    @Test void concurrentCasConflictReturnsLatestSnapshotWithoutPollutingOriginal() {
        var latestTrip = mapper.convertValue(session.trip(), Map.class); latestTrip.put("version", 2);
        var latest = stored("session", "USER", "actual-owner", latestTrip, 2);
        doReturn(PlannerSessionRepositoryPort.MutationResult.conflict(latest)).when(applicationRepository)
                .update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
        var result = application.mutateStops(new PlanningActor("actual-owner", false), "session", command("delete"));
        assertEquals(409, result.status()); assertEquals(latestTrip, result.body().get("trip"));
        assertEquals(before, mapper.valueToTree(session.trip()).toString());
        verifyNoInteractions(domainRepository);
    }
    @Test void guestCapabilityMustResolveTheExactRequestedSession() {
        var other = stored("different-session", "ANONYMOUS", "guest-owner", session.trip(), 1);
        when(applicationRepository.findAnonymousByAccessToken("capability")).thenReturn(other);
        var request = command("delete"); request.setSessionAccessToken("capability");
        assertEquals(404, application.mutateStops(new PlanningActor("anonymous", true), "session", request).status());
        verify(applicationRepository, never()).find(eq("session"), eq("ANONYMOUS"), anyString(), anyString());
        verify(applicationRepository, never()).update(any(), anyMap(), anyInt(), anyString(), anyString(), anyString(), anyList());
        verifyNoInteractions(domainRepository);
    }
    private PlannerMutationRequest command(String operation) {
        var request = new PlannerMutationRequest(); request.setOperation(operation); request.setStopId("original");
        request.setTargetStopId("original"); request.setExpectedVersion(1); request.setDay(1); return request;
    }
    private PlannerSessionRepositoryPort.StoredSession stored(String id, String type, String owner, Map<String, Object> trip, int version) {
        return new PlannerSessionRepositoryPort.StoredSession(id, type, owner, "", "", "", Map.of("destination", "测试城"),
                AppliedPreferencesSnapshot.empty(), trip, version, version, "DRAFT", 1, version);
    }
}
