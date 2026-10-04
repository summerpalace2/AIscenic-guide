package com.ai.guide.application.trip;

import com.ai.guide.domain.trip.gateway.TripPlanPdfExportPort;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import com.ai.guide.domain.trip.service.TripService;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TripApplicationServiceTest {

    private final TripService tripService = mock(TripService.class);
    private final TripPlanPdfExportPort tripPlanPdfExportPort = mock(TripPlanPdfExportPort.class);
    private final TripApplicationService applicationService =
            new TripApplicationService(tripService, tripPlanPdfExportPort);

    @Test
    void listTripsDelegatesToDomainService() {
        Trip trip = sampleTrip("trip-1", 1);
        when(tripService.list("owner-1")).thenReturn(List.of(trip));

        List<Trip> result = applicationService.listTrips("owner-1");

        assertEquals(1, result.size());
        assertEquals("trip-1", result.get(0).id());
        verify(tripService).list("owner-1");
    }

    @Test
    void getTripDelegatesToDomainService() {
        Trip trip = sampleTrip("trip-1", 1);
        when(tripService.get("owner-1", "trip-1")).thenReturn(trip);

        Trip result = applicationService.getTrip("owner-1", "trip-1");

        assertEquals(trip, result);
        verify(tripService).get("owner-1", "trip-1");
    }

    @Test
    void createTripDelegatesToDomainService() {
        Trip trip = sampleTrip("trip-1", 1);
        Map<String, Object> req = Map.of("title", "测试");
        TripService.OperationResult opResult = TripService.OperationResult.success(201, "已创建", "CREATED", trip);
        when(tripService.create("owner-1", req)).thenReturn(opResult);

        TripService.OperationResult result = applicationService.createTrip("owner-1", req);

        assertEquals(opResult, result);
        verify(tripService).create("owner-1", req);
    }

    @Test
    void updateTripDelegatesToDomainService() {
        Trip trip = sampleTrip("trip-1", 2);
        Map<String, Object> req = Map.of("title", "测试2");
        TripService.OperationResult opResult = TripService.OperationResult.success(200, "已更新", "UPDATED", trip);
        when(tripService.update("owner-1", "trip-1", req)).thenReturn(opResult);

        TripService.OperationResult result = applicationService.updateTrip("owner-1", "trip-1", req);

        assertEquals(opResult, result);
        verify(tripService).update("owner-1", "trip-1", req);
    }

    @Test
    void deleteTripDelegatesToDomainService() {
        Map<String, Object> req = Map.of("expectedVersion", 2);
        TripService.OperationResult opResult = TripService.OperationResult.deleted("trip-1", 2);
        when(tripService.delete("owner-1", "trip-1", req)).thenReturn(opResult);

        TripService.OperationResult result = applicationService.deleteTrip("owner-1", "trip-1", req);

        assertEquals(opResult, result);
        verify(tripService).delete("owner-1", "trip-1", req);
    }

    @Test
    void getVersionsDelegatesToDomainService() {
        TripVersion v = new TripVersion("v-1", "trip-1", 1, Map.of(), "初始保存", 1L, "owner-1");
        when(tripService.versions("owner-1", "trip-1")).thenReturn(List.of(v));

        List<TripVersion> result = applicationService.getVersions("owner-1", "trip-1");

        assertEquals(1, result.size());
        verify(tripService).versions("owner-1", "trip-1");
    }

    @Test
    void getVersionDelegatesToDomainService() {
        TripVersion v = new TripVersion("v-1", "trip-1", 1, Map.of(), "初始保存", 1L, "owner-1");
        when(tripService.version("owner-1", "trip-1", 1)).thenReturn(v);

        TripVersion result = applicationService.getVersion("owner-1", "trip-1", 1);

        assertEquals(v, result);
        verify(tripService).version("owner-1", "trip-1", 1);
    }

    @Test
    void replanTripDelegatesToDomainService() {
        Trip trip = sampleTrip("trip-1", 2);
        Map<String, Object> req = Map.of("targetStopId", "stop-1");
        TripService.OperationResult opResult = TripService.OperationResult.success(200, "重规划已保存", "UPDATED", trip);
        when(tripService.replan("owner-1", "trip-1", req)).thenReturn(opResult);

        TripService.OperationResult result = applicationService.replanTrip("owner-1", "trip-1", req);

        assertEquals(opResult, result);
        verify(tripService).replan("owner-1", "trip-1", req);
    }

    @Test
    void exportTripPdfReturnsPdfWhenFound() {
        Trip trip = sampleTrip("trip-1", 1);
        when(tripService.get("owner-1", "trip-1")).thenReturn(trip);
        byte[] expectedBytes = new byte[]{1, 2, 3, 4};
        when(tripPlanPdfExportPort.export(any())).thenReturn(expectedBytes);

        TripApplicationService.PdfExportResult result = applicationService.exportTripPdf("owner-1", "trip-1");

        assertNotNull(result);
        assertEquals("重庆行程", result.title());
        assertArrayEquals(expectedBytes, result.pdfBytes());
        verify(tripPlanPdfExportPort).export(any());
    }

    @Test
    void exportTripPdfReturnsNullWhenNotFound() {
        when(tripService.get("owner-1", "trip-unknown")).thenReturn(null);

        TripApplicationService.PdfExportResult result = applicationService.exportTripPdf("owner-1", "trip-unknown");

        assertNull(result);
    }

    private Trip sampleTrip(String id, int version) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("formalTripId", id);
        plan.put("days", List.of());
        return new Trip(id, "owner-1", "重庆行程", "ACTIVE", version,
                1L, 2L, plan);
    }
}
