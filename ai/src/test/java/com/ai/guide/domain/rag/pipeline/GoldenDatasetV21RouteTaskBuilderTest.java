package com.ai.guide.domain.rag.pipeline;


import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetV21RouteTaskBuilderTest {

    private static final Path ROUTE_GRAPH = Path.of("src", "test", "resources", "golden-dataset-v2-1",
            "golden_route_graph_p0.json");

    @Test
    void expandsAllP0EdgesAndPreservesDirection() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(ROUTE_GRAPH), "Skipping: golden_route_graph_p0.json not present");
        var tasks = new GoldenDatasetV21RouteTaskBuilder().build(ROUTE_GRAPH);

        assertEquals(14, tasks.size());
        assertEquals("ROUTE-EDGE-P0-001", tasks.get(0).taskId());
        assertEquals("hongyadong", tasks.get(0).fromEntityId());
        assertEquals("jiefangbei", tasks.get(0).toEntityId());
        assertEquals("WALKING", tasks.get(0).travelMode());
        assertEquals("/v5/direction/walking", tasks.get(0).endpoint());
        assertEquals("/v5/direction/transit/integrated", tasks.get(5).endpoint());
        assertTrue(tasks.stream().allMatch(task -> task.priority().equals("P0")));
    }

    @Test
    void doesNotInventRouteMetricsBeforeCoordinateAndApiResolution() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(ROUTE_GRAPH), "Skipping: golden_route_graph_p0.json not present");
        var tasks = new GoldenDatasetV21RouteTaskBuilder().build(ROUTE_GRAPH);
        var transit = tasks.stream()
                .filter(task -> task.edgeId().equals("EDGE-P0-006"))
                .findFirst()
                .orElseThrow();

        assertEquals("TRANSIT", transit.travelMode());
        assertEquals("重庆 李子坝单轨穿楼观景平台", transit.originQuery());
        assertTrue(transit.requiresCoordinateResolution());
        assertEquals("PENDING", transit.status());
        assertNull(transit.rawResponseRef());
        assertNull(transit.distanceMeters());
        assertNull(transit.durationSeconds());
        assertNull(transit.walkingDistanceMeters());
    }
}
