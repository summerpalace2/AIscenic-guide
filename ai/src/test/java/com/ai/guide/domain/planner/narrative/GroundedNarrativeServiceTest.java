package com.ai.guide.domain.planner.narrative;


import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class GroundedNarrativeServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void test01_featureFlagDisabledDoesNotCallModel() {
        AtomicInteger callCount = new AtomicInteger(0);
        GroundedNarrativeService service = new GroundedNarrativeService(
                (systemPrompt, userPrompt) -> {
                    callCount.incrementAndGet();
                    return "{}";
                },
                objectMapper,
                false, // enabled = false
                5000L,
                true   // credentialsAvailable = true
        );

        Map<String, Object> trip = createSampleTrip();
        boolean result = service.enhanceTrip(trip, createSampleConstraints(), createSampleAppliedPreferences());

        assertFalse(result, "When disabled, enhanceTrip should return false");
        assertEquals(0, callCount.get(), "Model must not be called when feature flag is disabled");
        assertEquals("TEMPLATE", trip.get("explanationSource"));
    }

    @Test
    void test02_missingCredentialsDoesNotCallModel() {
        AtomicInteger callCount = new AtomicInteger(0);
        GroundedNarrativeService service = new GroundedNarrativeService(
                (systemPrompt, userPrompt) -> {
                    callCount.incrementAndGet();
                    return "{}";
                },
                objectMapper,
                true,  // enabled = true
                5000L,
                false  // credentialsAvailable = false
        );

        Map<String, Object> trip = createSampleTrip();
        boolean result = service.enhanceTrip(trip, createSampleConstraints(), createSampleAppliedPreferences());

        assertFalse(result, "When credentials missing, enhanceTrip should return false");
        assertEquals(0, callCount.get(), "Model must not be called when credentials are missing");
        assertEquals("TEMPLATE", trip.get("explanationSource"));
    }

    @Test
    void test03_modelSuccessReturnsLlmGroundedAndUpdatesReasons() {
        AtomicInteger callCount = new AtomicInteger(0);
        String modelJsonResponse = """
                {
                  "tripSummary": "针对带父母慢节奏出行的渝中文博与开阔夜景定制行程，兼顾平街舒适度与经典地标。",
                  "stopReasons": {
                    "day1-jiefangbei": "重庆母城核心平街地标，地势平缓且商圈配套完善，非常适合长辈适应山城节奏。",
                    "day1-hongyadong": "巴渝依山吊脚楼与两江交汇夜景主场，沧白路高位进入可免除爬坡疲惫。"
                  }
                }
                """;

        GroundedNarrativeService service = new GroundedNarrativeService(
                (systemPrompt, userPrompt) -> {
                    callCount.incrementAndGet();
                    return modelJsonResponse;
                },
                objectMapper,
                true,
                5000L,
                true
        );

        Map<String, Object> trip = createSampleTrip();
        boolean result = service.enhanceTrip(trip, createSampleConstraints(), createSampleAppliedPreferences());

        assertTrue(result, "When model succeeds, enhanceTrip should return true");
        assertEquals(1, callCount.get(), "Model must be called exactly once for the entire trip");
        assertEquals("LLM_GROUNDED", trip.get("explanationSource"));
        assertEquals("针对带父母慢节奏出行的渝中文博与开阔夜景定制行程，兼顾平街舒适度与经典地标。", trip.get("summary"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");

        assertEquals("重庆母城核心平街地标，地势平缓且商圈配套完善，非常适合长辈适应山城节奏。",
                stops.get(0).get("recommendationReason"));
        assertEquals("巴渝依山吊脚楼与两江交汇夜景主场，沧白路高位进入可免除爬坡疲惫。",
                stops.get(1).get("recommendationReason"));
    }

    @Test
    void test04_modelExceptionFallsBackToTemplateWithoutFailing() {
        AtomicInteger callCount = new AtomicInteger(0);
        GroundedNarrativeService service = new GroundedNarrativeService(
                (systemPrompt, userPrompt) -> {
                    callCount.incrementAndGet();
                    throw new RuntimeException("DeepSeek 503 Service Unavailable");
                },
                objectMapper,
                true,
                5000L,
                true
        );

        Map<String, Object> trip = createSampleTrip();
        String originalSummary = String.valueOf(trip.get("summary"));
        boolean result = service.enhanceTrip(trip, createSampleConstraints(), createSampleAppliedPreferences());

        assertFalse(result, "When model fails, enhanceTrip should return false gracefully");
        assertEquals(1, callCount.get());
        assertEquals("TEMPLATE", trip.get("explanationSource"));
        assertEquals(originalSummary, trip.get("summary"), "Original summary must remain intact");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");

        assertEquals("模板初始理由1", stops.get(0).get("recommendationReason"));
        assertEquals("模板初始理由2", stops.get(1).get("recommendationReason"));
    }

    @Test
    void test05_modelTimeoutFallsBackToTemplate() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            GroundedNarrativeService service = new GroundedNarrativeService(
                    (systemPrompt, userPrompt) -> {
                        Thread.sleep(100);
                        return "{}";
                    },
                    objectMapper,
                    true,
                    10L, // timeout = 10ms
                    true,
                    executor
            );

            Map<String, Object> trip = createSampleTrip();
            boolean result = service.enhanceTrip(trip, createSampleConstraints(), createSampleAppliedPreferences());

            assertFalse(result, "When model times out, enhanceTrip should return false gracefully");
            assertEquals("TEMPLATE", trip.get("explanationSource"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void test06_unknownStopIdsInResponseAreSafelyIgnored() {
        String modelJsonResponse = """
                {
                  "tripSummary": "更新后的行程总览",
                  "stopReasons": {
                    "day1-jiefangbei": "更新的解放碑理由",
                    "non-existent-stop-999": "这个站点根本不存在于当前行程中，应被完全忽略",
                    "fake-venue-xyz": "另一个虚构站点理由"
                  }
                }
                """;

        GroundedNarrativeService service = new GroundedNarrativeService(
                (systemPrompt, userPrompt) -> modelJsonResponse,
                objectMapper,
                true,
                5000L,
                true
        );

        Map<String, Object> trip = createSampleTrip();
        boolean result = service.enhanceTrip(trip, createSampleConstraints(), createSampleAppliedPreferences());

        assertTrue(result);
        assertEquals("LLM_GROUNDED", trip.get("explanationSource"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");

        assertEquals(2, stops.size(), "Stops count must not change when model returns extra stopIds");
        assertEquals("day1-jiefangbei", stops.get(0).get("id"));
        assertEquals("day1-hongyadong", stops.get(1).get("id"));
        assertEquals("更新的解放碑理由", stops.get(0).get("recommendationReason"));
        assertEquals("模板初始理由2", stops.get(1).get("recommendationReason"), "Unmentioned stop preserves template reason");
    }

    @Test
    void test07_tripStructureInvariantsPreservedAcrossEnhancement() {
        String modelJsonResponse = """
                {
                  "tripSummary": "个性化总结",
                  "stopReasons": {
                    "day1-jiefangbei": "理由1",
                    "day1-hongyadong": "理由2"
                  }
                }
                """;

        GroundedNarrativeService service = new GroundedNarrativeService(
                (systemPrompt, userPrompt) -> modelJsonResponse,
                objectMapper,
                true,
                5000L,
                true
        );

        Map<String, Object> trip = createSampleTrip();
        String originalId = String.valueOf(trip.get("id"));
        int originalVersion = (int) trip.get("version");
        String originalTitle = String.valueOf(trip.get("title"));

        boolean result = service.enhanceTrip(trip, createSampleConstraints(), createSampleAppliedPreferences());
        assertTrue(result);

        assertEquals(originalId, trip.get("id"), "Trip id must not be mutated");
        assertEquals(originalVersion, trip.get("version"), "Trip version must not be mutated");
        assertEquals(originalTitle, trip.get("title"), "Trip title must not be mutated");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        assertEquals(1, days.size());
        assertEquals(1, days.get(0).get("day"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");
        assertEquals(2, stops.size());
        assertEquals("day1-jiefangbei", stops.get(0).get("id"));
        assertEquals("cq-jiefangbei", stops.get(0).get("venueId"));
        assertEquals("14:30", stops.get(0).get("time"));
        assertEquals("约 90 分钟", stops.get(0).get("duration"));

        assertEquals("day1-hongyadong", stops.get(1).get("id"));
        assertEquals("cq-hongyadong", stops.get(1).get("venueId"));
        assertEquals("19:30", stops.get(1).get("time"));
        assertEquals("约 90 分钟", stops.get(1).get("duration"));
    }

    @Test
    void test08_unsupportedAbsoluteAssertionTriggersFallback() {
        // 输入只有 accessibility=SUPPORTED、低难度（不含“全馆”），模型返回“全馆无障碍”，必须安全回退
        String modelJsonResponse = """
                {
                  "tripSummary": "两日慢节奏文化行程",
                  "stopReasons": {
                    "day1-museum": "全馆无障碍平滑观展，壮丽三峡与巴渝历史史诗，适合长辈舒适游览。"
                  }
                }
                """;

        GroundedNarrativeService service = new GroundedNarrativeService(
                (systemPrompt, userPrompt) -> modelJsonResponse,
                objectMapper,
                true,
                5000L,
                true
        );

        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("id", "draft-cq-1");
        trip.put("version", 1);
        trip.put("summary", "模板默认行程总览");
        trip.put("explanationSource", "TEMPLATE");

        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", "day1-museum");
        stop.put("name", "重庆中国三峡博物馆");
        stop.put("accessibility", "SUPPORTED");
        stop.put("walkDifficulty", "低");
        stop.put("summary", "馆藏巴蜀青铜器与三峡变迁史诗"); // 无“全馆”
        stop.put("ticket", "建议刷身份证进馆");
        stop.put("recommendationReason", "模板初始理由");

        Map<String, Object> day = new LinkedHashMap<>();
        day.put("day", 1);
        day.put("stops", List.of(stop));
        trip.put("days", List.of(day));

        boolean result = service.enhanceTrip(trip, createSampleConstraints(), createSampleAppliedPreferences());

        assertFalse(result, "Unsupported absolute assertion '全馆' must trigger fallback to TEMPLATE");
        assertEquals("TEMPLATE", trip.get("explanationSource"));
        assertEquals("模板初始理由", stop.get("recommendationReason"), "Stop reason must retain template reason");
    }

    @Test
    void test09_supportedAssertionRetainsLlmGrounded() {
        // 输入明确包含“无需预约”，模型返回“无需预约”，允许保留 LLM_GROUNDED
        String modelJsonResponse = """
                {
                  "tripSummary": "两日经典夜景文化行程",
                  "stopReasons": {
                    "day1-hongyadong": "依山而建吊脚楼夜景璀璨，无需预约即可错峰观景。"
                  }
                }
                """;

        GroundedNarrativeService service = new GroundedNarrativeService(
                (systemPrompt, userPrompt) -> modelJsonResponse,
                objectMapper,
                true,
                5000L,
                true
        );

        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("id", "draft-cq-1");
        trip.put("version", 1);
        trip.put("summary", "模板默认行程总览");
        trip.put("explanationSource", "TEMPLATE");

        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", "day1-hongyadong");
        stop.put("name", "洪崖洞");
        stop.put("ticket", "免费开放 · 无需预约"); // 明确包含“无需预约”与“免费”
        stop.put("summary", "依山而建吊脚楼");
        stop.put("recommendationReason", "模板初始理由");

        Map<String, Object> day = new LinkedHashMap<>();
        day.put("day", 1);
        day.put("stops", List.of(stop));
        trip.put("days", List.of(day));

        boolean result = service.enhanceTrip(trip, createSampleConstraints(), createSampleAppliedPreferences());

        assertTrue(result, "Supported assertion '无需预约' should be allowed to retain LLM_GROUNDED");
        assertEquals("LLM_GROUNDED", trip.get("explanationSource"));
        assertEquals("依山而建吊脚楼夜景璀璨，无需预约即可错峰观景。", stop.get("recommendationReason"));
    }

    @Test
    void modelCallUsesDedicatedExecutorBoundary() {
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "test-planner-llm");
            thread.setDaemon(true);
            return thread;
        });
        try {
            GroundedNarrativeService service = new GroundedNarrativeService(
                    (systemPrompt, userPrompt) -> {
                        threadName.set(Thread.currentThread().getName());
                        return "{\"tripSummary\":\"更新后的行程概览\",\"stopReasons\":{}}";
                    }, objectMapper, true, 5000L, true, executor);

            boolean result = service.enhanceTrip(
                    createSampleTrip(), createSampleConstraints(), createSampleAppliedPreferences());

            assertTrue(result);
            assertEquals("test-planner-llm", threadName.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private Map<String, Object> createSampleTrip() {
        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("id", "draft-cq-1");
        trip.put("version", 1);
        trip.put("title", "重庆2天1夜·专属定制方案");
        trip.put("subtitle", "少走路优先 · 带父母 · 人文 + 夜景");
        trip.put("summary", "模板默认行程总览");
        trip.put("explanationSource", "TEMPLATE");
        trip.put("routeDataStatus", "VERIFIED_AMAP");
        trip.put("sourceMode", "高德实时接口");

        List<Map<String, Object>> stops = new ArrayList<>();

        Map<String, Object> stop1 = new LinkedHashMap<>();
        stop1.put("id", "day1-jiefangbei");
        stop1.put("venueId", "cq-jiefangbei");
        stop1.put("name", "解放碑步行街");
        stop1.put("district", "渝中区");
        stop1.put("time", "14:30");
        stop1.put("duration", "约 90 分钟");
        stop1.put("walkDifficulty", "低");
        stop1.put("indoor", false);
        stop1.put("recommendationReason", "模板初始理由1");
        stop1.put("scoreBreakdown", Map.of("reasonCodes", List.of("WALKING_TOLERANCE_MATCH", "COMPANION_MATCH_PARENT"), "total", 85));
        stops.add(stop1);

        Map<String, Object> stop2 = new LinkedHashMap<>();
        stop2.put("id", "day1-hongyadong");
        stop2.put("venueId", "cq-hongyadong");
        stop2.put("name", "洪崖洞");
        stop2.put("district", "渝中区");
        stop2.put("time", "19:30");
        stop2.put("duration", "约 90 分钟");
        stop2.put("walkDifficulty", "中");
        stop2.put("indoor", false);
        stop2.put("recommendationReason", "模板初始理由2");
        stop2.put("scoreBreakdown", Map.of("reasonCodes", List.of("INTEREST_MATCH_NIGHT", "SCENIC_CLUSTER_MATCH"), "total", 90));
        stop2.put("resolvedRouteCost", Map.of("durationText", "12分钟", "status", "VERIFIED_AMAP"));
        stops.add(stop2);

        Map<String, Object> day1 = new LinkedHashMap<>();
        day1.put("day", 1);
        day1.put("stops", stops);

        trip.put("days", List.of(day1));
        return trip;
    }

    private TravelConstraints createSampleConstraints() {
        TravelConstraints c = new TravelConstraints();
        c.setDurationDays(2);
        c.setCompanions("带父母");
        c.setWalkingTolerance("低");
        c.setInterests(List.of("人文历史", "山城夜景"));
        return c;
    }

    private AppliedPreferencesSnapshot createSampleAppliedPreferences() {
        return AppliedPreferencesSnapshot.create(
                Map.of("walkingTolerance", "LOW", "interests", List.of("人文", "夜景")),
                1,
                1,
                "v1",
                objectMapper
        );
    }
}
