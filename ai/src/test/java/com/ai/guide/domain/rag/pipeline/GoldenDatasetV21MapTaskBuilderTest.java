package com.ai.guide.domain.rag.pipeline;


import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldenDatasetV21MapTaskBuilderTest {

    private static final Path CONTRACT = Path.of("src", "test", "resources", "golden-dataset-v2-1",
            "map_api_required_all_9.json");

    @Test
    void expandsAllRequiredFieldsIntoIndependentPendingTasks() throws Exception {
        var tasks = new GoldenDatasetV21MapTaskBuilder().build(CONTRACT);

        assertEquals(21, tasks.size());
        assertEquals(21, new HashSet<>(tasks.stream().map(GoldenDatasetV21MapTaskBuilder.MapCollectionTask::taskId).toList()).size());
        assertEquals(9, tasks.stream().map(GoldenDatasetV21MapTaskBuilder.MapCollectionTask::entityId).distinct().count());

        var hongyadongEntrance = tasks.stream()
                .filter(task -> task.taskId().equals("MAP-hongyadong-entrance_1f_gps"))
                .findFirst()
                .orElseThrow();
        assertEquals("重庆洪崖洞民俗风貌区 1F入口", hongyadongEntrance.query());
        assertEquals("PENDING", hongyadongEntrance.status());
        assertEquals("AMAP", hongyadongEntrance.provider());
        assertEquals(3, hongyadongEntrance.endpointCandidates().size());
        assertNull(hongyadongEntrance.rawResponseRef());
        assertNull(hongyadongEntrance.normalizedValue());
    }

    @Test
    void preservesEntityAndFieldCoverageWithoutInventingTasks() throws Exception {
        var tasks = new GoldenDatasetV21MapTaskBuilder().build(CONTRACT);
        Set<String> expected = Set.of(
                "hongyadong:coordinates_gps", "hongyadong:entrance_1f_gps", "hongyadong:entrance_11f_gps",
                "liziba_viewpoint:coordinates_gps", "liziba_viewpoint:plaza_anchor_gps",
                "liziba_station:coordinates_gps", "liziba_station:station_exit_1_gps", "liziba_station:station_exit_2_gps",
                "eling_testbed2:coordinates_gps", "eling_testbed2:east_gate_gps",
                "shancheng_trail:coordinates_gps", "shancheng_trail:entrance_top_zhongxing_rd_gps", "shancheng_trail:entrance_bottom_nanqu_rd_gps",
                "huguang_guild_hall:coordinates_gps", "huguang_guild_hall:main_gate_changbin_rd_gps",
                "jiefangbei:coordinates_gps", "jiefangbei:monument_center_gps",
                "yangtze_cableway:coordinates_north_station_gps", "yangtze_cableway:coordinates_south_station_gps",
                "three_gorges_museum:coordinates_gps", "three_gorges_museum:museum_main_entrance_gps"
        );
        Set<String> actual = tasks.stream()
                .map(task -> task.entityId() + ":" + task.field())
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(expected, actual);
        assertTrue(tasks.stream().allMatch(task -> task.revisionId().equals("GOLDEN_DATASET_CANDIDATE_V2_1")));
        assertTrue(tasks.stream().allMatch(task -> task.requestTime() == null));
    }
}
