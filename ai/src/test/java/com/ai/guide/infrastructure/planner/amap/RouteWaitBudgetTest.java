package com.ai.guide.infrastructure.planner.amap;

import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoRouteOutcome;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import static org.junit.jupiter.api.Assertions.*;

class RouteWaitBudgetTest {
    @Test
    void fullHttpQueueReturnsUnavailableWithoutInlineProviderWork() throws Exception {
        var client = org.mockito.Mockito.mock(com.ai.guide.infrastructure.rag.amap.AmapWebServiceClient.class);
        org.mockito.Mockito.when(client.isConfigured()).thenReturn(true);
        var service = new AmapRouteService(client, 500, 500, 1, new AmapRequestCache(10), false, false,
                1000, 1000, 1000, 1000);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        try {
            var field = AmapRouteService.class.getDeclaredField("executor");
            field.setAccessible(true);
            var executor = (java.util.concurrent.ThreadPoolExecutor) field.get(service);
            executor.execute(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
            for (int i = 0; i < 256; i++) executor.execute(() -> { });
            assertEquals(GeoOutcomeStatus.UNAVAILABLE, service.searchPoi("queued", "city").status());
            org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).get(org.mockito.ArgumentMatchers.any());
        } finally { release.countDown(); service.close(); }
    }

    @Test
    void timedOutCacheFollowerDoesNotCancelAnotherRequestsProviderCall() throws Exception {
        var rest = new org.springframework.web.client.RestTemplate();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(rest).build();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("/v5/place/text"))).andRespond(request -> {
                    started.countDown();
                    try { release.await(); } catch (InterruptedException error) { throw new java.io.IOException(error); }
                    return org.springframework.test.web.client.response.MockRestResponseCreators
                            .withSuccess("{\"status\":\"1\",\"pois\":[]}", org.springframework.http.MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        var service = new AmapRouteService(new com.ai.guide.infrastructure.rag.amap.AmapWebServiceClient(
                "https://restapi.amap.com", "test", rest));
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var owner = caller.submit(() -> service.searchPoi("shared", "city"));
            assertTrue(started.await(1, java.util.concurrent.TimeUnit.SECONDS));
            try (var scope = com.ai.guide.common.context.OperationDeadline.open(30)) {
                assertEquals(GeoOutcomeStatus.TIMEOUT, service.searchPoi("shared", "city").status());
            }
            assertFalse(owner.isDone());
            release.countDown();
            assertEquals(GeoOutcomeStatus.SUCCESS, owner.get(1, java.util.concurrent.TimeUnit.SECONDS).status());
            assertEquals(GeoOutcomeStatus.SUCCESS, service.searchPoi("shared", "city").status());
            server.verify();
        } finally { release.countDown(); caller.shutdownNow(); service.close(); }
    }

    @Test
    void oneSlowHttpCallUsesRemainingRequestBudgetAndStartsNoRetryAfterExpiry() throws Exception {
        var rest = new org.springframework.web.client.RestTemplate();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(rest).build();
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                org.hamcrest.Matchers.containsString("/v5/place/text"))).andRespond(request -> {
                    started.countDown();
                    // Simulate a provider which ignores socket interruption until independently released.
                    while (release.getCount() > 0) {
                        try { release.await(); } catch (InterruptedException ignored) { }
                    }
                    return org.springframework.test.web.client.response.MockRestResponseCreators
                            .withSuccess("{\"status\":\"1\",\"pois\":[]}", org.springframework.http.MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });
        var service = new AmapRouteService(new com.ai.guide.infrastructure.rag.amap.AmapWebServiceClient(
                "https://restapi.amap.com", "test", rest));
        try {
            long startedAt = System.nanoTime();
            try (var scope = com.ai.guide.common.context.OperationDeadline.open(120)) {
                assertEquals(GeoOutcomeStatus.TIMEOUT, service.searchPoi("test", "city").status());
                assertTrue(com.ai.guide.common.context.OperationDeadline.expired());
                assertEquals(GeoOutcomeStatus.TIMEOUT, service.searchPoi("other", "city").status());
            }
            assertEquals(0L, started.getCount());
            assertTrue((System.nanoTime() - startedAt) / 1_000_000L < 1000L);
            server.verify();
        } finally { release.countDown(); service.close(); }
    }

    @Test
    void queuedPairWorkTimesOutAndIsCancelled() throws Exception {
        AmapRouteService service = new AmapRouteService(null);
        try {
            FutureTask<GeoRouteOutcome> queued = new FutureTask<>(() -> null);
            Method wait = AmapRouteService.class.getDeclaredMethod("joinOutcome", Future.class, long.class);
            wait.setAccessible(true);
            GeoRouteOutcome outcome = (GeoRouteOutcome) wait.invoke(service, queued, System.nanoTime() - 1);
            assertEquals(GeoOutcomeStatus.TIMEOUT, outcome.status());
            assertTrue(queued.isCancelled());
        } finally { service.close(); }
    }

    @Test
    void neverStartedBatchReturnsUnverifiedRoutesWithinOneBudget() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        AmapPlannerGateway gateway = new AmapPlannerGateway(null, (AttractionService) null, 3, queued::add);
        gateway.setRouteBatchTimeoutMs(30);
        List<Map<String, Object>> stops = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> stop = new LinkedHashMap<>();
            stop.put("name", "stop-" + i);
            stop.put("location", "120,30");
            stops.add(stop);
        }
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("city", "测试市");
        trip.put("days", List.of(Map.of("stops", stops)));
        Method hydrate = AmapPlannerGateway.class.getDeclaredMethod("hydrateRoutes", Map.class);
        hydrate.setAccessible(true);
        long started = System.nanoTime();
        assertEquals(0, hydrate.invoke(gateway, trip));
        assertTrue((System.nanoTime() - started) / 1_000_000L < 1000L);
        assertEquals(3, queued.size());
        for (int i = 1; i < stops.size(); i++) {
            Map<?, ?> route = (Map<?, ?>) stops.get(i).get("routeFromPrevious");
            assertEquals("ESTIMATED", route.get("routeDataStatus"));
            assertEquals(true, route.get("fallback"));
            assertEquals("未返回", route.get("selectedMode"));
        }
        // Cancelled queued tasks must not invoke a late provider or change the returned trip.
        queued.forEach(Runnable::run);
        assertEquals("未返回", ((Map<?, ?>) stops.get(1).get("routeFromPrevious")).get("selectedMode"));
    }
}
