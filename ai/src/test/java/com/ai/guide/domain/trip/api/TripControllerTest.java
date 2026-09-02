package com.ai.guide.domain.trip.api;


import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.trip.service.TripPlanPdfService;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import com.ai.guide.domain.trip.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TripControllerTest {

    private final TripService tripService = mock(TripService.class);
    private final TripPlanPdfService tripPlanPdfService = mock(TripPlanPdfService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new TripController(tripService, tripPlanPdfService)).build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listIsScopedToCurrentOwner() throws Exception {
        UserContext.set("owner-1", "TOURIST");
        Trip trip = trip("trip-1", 1);
        when(tripService.list("owner-1")).thenReturn(List.of(trip));

        mockMvc.perform(get("/ai/trips"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("trip-1"));

        verify(tripService).list("owner-1");
    }

    @Test
    void createKeepsTripAsMutationPayload() throws Exception {
        UserContext.set("owner-1", "TOURIST");
        Trip trip = trip("trip-1", 1);
        when(tripService.create(eq("owner-1"), any()))
                .thenReturn(TripService.OperationResult.success(201, "行程已创建", "CREATED", trip));

        mockMvc.perform(post("/ai/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"重庆行程\",\"plan\":{\"days\":[]},\"idempotencyKey\":\"k1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("trip-1"))
                .andExpect(jsonPath("$.data.currentVersion").value(1));
    }

    @Test
    void getReturnsOwnerScopedTrip() throws Exception {
        UserContext.set("owner-1", "TOURIST");
        when(tripService.get("owner-1", "trip-1")).thenReturn(trip("trip-1", 2));

        mockMvc.perform(get("/ai/trips/trip-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("trip-1"))
                .andExpect(jsonPath("$.data.currentVersion").value(2));
    }

    @Test
    void updateReturnsCasConflictMetadata() throws Exception {
        UserContext.set("owner-1", "TOURIST");
        TripService.OperationResult conflict = TripService.OperationResult.conflict("trip-1", 1, 2);
        when(tripService.update(eq("owner-1"), eq("trip-1"), any())).thenReturn(conflict);

        mockMvc.perform(put("/ai/trips/trip-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"plan\":{\"days\":[]}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.tripId").value("trip-1"))
                .andExpect(jsonPath("$.data.expectedVersion").value(1))
                .andExpect(jsonPath("$.data.currentVersion").value(2))
                .andExpect(jsonPath("$.data.operationStatus").value("CONFLICT"));
    }

    @Test
    void replanExposesServerSelectedReplacementMetadata() throws Exception {
        UserContext.set("owner-1", "TOURIST");
        Trip trip = trip("trip-1", 2);
        when(tripService.replan(eq("owner-1"), eq("trip-1"), any()))
                .thenReturn(TripService.OperationResult.success(
                        200, "局部重规划已保存", "UPDATED", trip,
                        "cq-grand-theatre", List.of("day-1")));

        mockMvc.perform(post("/ai/trips/trip-1/replan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetStopId\":\"day1-hongyadong\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.trip.id").value("trip-1"))
                .andExpect(jsonPath("$.data.tripId").value("trip-1"))
                .andExpect(jsonPath("$.data.currentVersion").value(2))
                .andExpect(jsonPath("$.data.operationStatus").value("UPDATED"))
                .andExpect(jsonPath("$.data.replacementVenueId").value("cq-grand-theatre"))
                .andExpect(jsonPath("$.data.changedSegments[0]").value("day-1"));
    }

    @Test
    void versionsAndVersionAreOwnerScoped() throws Exception {
        UserContext.set("owner-1", "TOURIST");
        TripVersion version = new TripVersion("version-1", "trip-1", 1,
                Map.of("days", List.of()), "初始保存", 1L, "owner-1");
        when(tripService.versions("owner-1", "trip-1")).thenReturn(List.of(version));
        when(tripService.version("owner-1", "trip-1", 1)).thenReturn(version);

        mockMvc.perform(get("/ai/trips/trip-1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].versionNumber").value(1));
        mockMvc.perform(get("/ai/trips/trip-1/versions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tripId").value("trip-1"));
    }

    @Test
    void deleteUsesOwnerScopeAndExpectedVersion() throws Exception {
        UserContext.set("owner-1", "TOURIST");
        when(tripService.delete(eq("owner-1"), eq("trip-1"), any()))
                .thenReturn(TripService.OperationResult.deleted("trip-1", 2));

        mockMvc.perform(delete("/ai/trips/trip-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(tripService).delete(eq("owner-1"), eq("trip-1"), any());
    }

    @Test
    void deleteWithoutExpectedVersionIsRejectedByServiceContract() throws Exception {
        UserContext.set("owner-1", "TOURIST");
        when(tripService.delete(eq("owner-1"), eq("trip-1"), any()))
                .thenReturn(TripService.OperationResult.badRequest("expectedVersion 为必填整数"));

        mockMvc.perform(delete("/ai/trips/trip-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.operationStatus").value("BAD_REQUEST"));
    }

    @Test
    void anonymousCannotMutateTrips() throws Exception {
        UserContext.clear();

        mockMvc.perform(post("/ai/trips")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plan\":{\"days\":[]}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        verify(tripService, never()).create(any(), any());
    }

    @Test
    void anonymousCannotReadTripListVersionsOrExport() throws Exception {
        UserContext.clear();

        mockMvc.perform(get("/ai/trips"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(get("/ai/trips/trip-1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/ai/trips/trip-1/versions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/ai/trips/trip-1/versions/1"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/ai/trips/trip-1/export"))
                .andExpect(status().isUnauthorized());

        verify(tripService, never()).list(any());
        verify(tripService, never()).get(any(), any());
        verify(tripService, never()).versions(any(), any());
        verify(tripService, never()).version(any(), any(), anyInt());
        verify(tripPlanPdfService, never()).export(any());
    }

    private Trip trip(String id, int version) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("formalTripId", id);
        plan.put("days", List.of());
        return new Trip(id, "owner-1", "重庆行程", "ACTIVE", version,
                1L, 2L, plan);
    }
}
