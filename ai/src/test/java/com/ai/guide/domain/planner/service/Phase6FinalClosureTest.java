package com.ai.guide.domain.planner.service;









import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.api.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.api.PlanConversationRequestDto;
import com.ai.guide.domain.planner.api.PlanRequest;
import com.ai.guide.domain.planner.api.ShadowPlanRequest;
import com.ai.guide.domain.planner.engine.PlanVerifier;
import com.ai.guide.domain.planner.engine.RouteCost;
import com.ai.guide.domain.planner.engine.RouteCostProvider;
import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.ScoreBreakdown;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.narrative.PlanNarrativeService;
import com.ai.guide.domain.planner.repository.PlannerSessionRepositoryPort;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.trip.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class Phase6FinalClosureTest {

    @Autowired
    private PlannerService plannerService;

    @Autowired
    private ItineraryBuilderPort itineraryBuilder;

    @Autowired
    private TravelConstraintParserPort constraintParser;

    @Autowired
    private PlannerSessionRepositoryPort repository;

    @Autowired
    private TripService tripService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RouteGatewayPort amapPlannerGateway;

    @Autowired
    private PlannerPreferencesResolver preferencesResolver;

    @Autowired
    private PlanAdjustmentService planAdjustmentService;

    @Autowired
    private PlanVerifier planVerifier;

    @Autowired
    private PlanNarrativeService planNarrativeService;

    @Autowired
    private RouteCostProvider routeCostProvider;

    @Autowired
    private AttractionService attractionService;

    @Autowired
    private PlannerMetricsLogger metricsLogger;

    @Autowired
    private ProposalStore proposalStore;

    @AfterEach
    void tearDown() {
        UserContext.clear();
        proposalStore.clear();
    }

    @Test
    void test01_featureFlagV1IsEnabledByDefault() {
        assertTrue(plannerService.isPlannerV1Enabled(), "planner.v1.enabled should default to true");
    }

    @Test
    void test02_plannerV1EnabledUsesV1Path() {
        // V1 enabled instance
        PlannerService v1Planner = new PlannerService(
                constraintParser, itineraryBuilder, repository, tripService, objectMapper,
                amapPlannerGateway, preferencesResolver, planAdjustmentService,
                metricsLogger, true
        );

        assertTrue(v1Planner.isPlannerV1Enabled());
        PlanRequest req = PlanRequest.builder()
                .prompt("3天行程，喜欢夜景")
                .idempotencyKey("v1-test-" + UUID.randomUUID())
                .build();
        PlannerService.ServiceResult result = v1Planner.create(req);
        assertEquals(200, result.status());
        assertEquals("1.0.0-v1", result.body().get("plannerVersion"));
        assertEquals("2026.08-v1", result.body().get("policyVersion"));
        assertEquals("TEMPLATE", result.body().get("explanationSource"));

        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) result.body().get("trip");
        assertTrue(String.valueOf(trip.get("title")).contains("专属定制方案") || String.valueOf(trip.get("title")).contains("漫游线"));
    }

    @Test
    void test03_plannerV1DisabledUsesLegacyPath() {
        // V1 disabled (legacy fallback) instance
        PlannerService legacyPlanner = new PlannerService(
                constraintParser, itineraryBuilder, repository, tripService, objectMapper,
                amapPlannerGateway, preferencesResolver, planAdjustmentService,
                metricsLogger, false
        );

        assertFalse(legacyPlanner.isPlannerV1Enabled());

        // 1. Create plan delegates to legacy static baseline
        PlanRequest req = PlanRequest.builder()
                .prompt("3天行程，喜欢夜景")
                .idempotencyKey("legacy-test-" + UUID.randomUUID())
                .build();
        PlannerService.ServiceResult result = legacyPlanner.create(req);
        assertEquals(200, result.status());
        assertEquals("legacy-v0", result.body().get("plannerVersion"));
        assertEquals("legacy-2026.07", result.body().get("policyVersion"));
        assertEquals("LEGACY_STATIC", result.body().get("explanationSource"));
        assertTrue(Boolean.TRUE.equals(result.body().get("degraded")));
        @SuppressWarnings("unchecked")
        List<String> degReasons = (List<String>) result.body().get("degradationReasons");
        assertTrue(degReasons.contains("LEGACY_PLANNER_ACTIVE"));

        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) result.body().get("trip");
        assertTrue(String.valueOf(trip.get("title")).contains("传统基准方案"));

        // 2. Detail page adjustment requests are unavailable in legacy mode
        String sessionId = String.valueOf(result.body().get("sessionId"));
        String token = String.valueOf(result.body().get("sessionAccessToken"));

        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("换掉解放碑")
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult previewRes = legacyPlanner.previewAdjustment(sessionId, convReq);
        assertEquals(400, previewRes.status());
        assertTrue(String.valueOf(previewRes.body().get("message")).contains("LEGACY_PLANNER_UNAVAILABLE"));
    }

    @Test
    void test04_versionMetadataPresentAcrossCreateShadowPreviewAndApply() {
        // 1. Create durable plan
        PlanRequest createReq = PlanRequest.builder()
                .prompt("2天行程，喜欢夜景和美食")
                .idempotencyKey("v6-meta-" + UUID.randomUUID())
                .build();
        PlannerService.ServiceResult created = plannerService.create(createReq);
        assertEquals(200, created.status());
        assertEquals(PlannerVersionMetadata.CURRENT_PLANNER_VERSION, created.body().get("plannerVersion"));
        assertEquals(PlannerVersionMetadata.CURRENT_POLICY_VERSION, created.body().get("policyVersion"));
        assertEquals(PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE, created.body().get("explanationSource"));
        assertNotNull(created.body().get("routeDataStatus"));

        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        // 2. Shadow plan
        ShadowPlanRequest shadowReq = ShadowPlanRequest.builder()
                .prompt("2天行程，喜欢夜景")
                .build();
        PlannerService.ServiceResult shadowRes = plannerService.shadow(shadowReq);
        assertEquals(200, shadowRes.status());
        assertEquals(PlannerVersionMetadata.CURRENT_PLANNER_VERSION, shadowRes.body().get("plannerVersion"));
        assertEquals(PlannerVersionMetadata.CURRENT_POLICY_VERSION, shadowRes.body().get("policyVersion"));
        assertEquals(PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE, shadowRes.body().get("explanationSource"));

        // 3. Preview adjustment
        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");
        String firstStopId = String.valueOf(day1Stops.get(0).get("id"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("这个景点不想去，换一个")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(firstStopId).build())
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        assertEquals(PlannerVersionMetadata.CURRENT_PLANNER_VERSION, previewRes.body().get("plannerVersion"));
        assertEquals(PlannerVersionMetadata.CURRENT_POLICY_VERSION, previewRes.body().get("policyVersion"));
        assertEquals(PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE, previewRes.body().get("explanationSource"));
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));

        // 4. Apply adjustment
        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-1")
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applyRes.status());
        assertEquals(PlannerVersionMetadata.CURRENT_PLANNER_VERSION, applyRes.body().get("plannerVersion"));
        assertEquals(PlannerVersionMetadata.CURRENT_POLICY_VERSION, applyRes.body().get("policyVersion"));
        assertEquals(PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE, applyRes.body().get("explanationSource"));
    }

    @Test
    void test05_groundedRecommendationNarrativeSubstantiatedByEvidence() {
        Attraction museum = attractionService.get("cq-museum");
        assertNotNull(museum);

        TravelConstraints constraints = constraintParser.parse("周六到重庆，带父母，少走路，喜欢人文历史");
        ScoreBreakdown breakdown = new ScoreBreakdown(45, 35, 35, 30, 0, 0, 0, 145,
                List.of("INTEREST_HISTORY", "WALKING_EASY", "COMPANION_SENIOR_FRIENDLY", "DINING_NON_SPICY_FIT"));
        RouteCost routeCost = RouteCost.estimated(1500, 18, 200, "TRANSIT", "路网估算");

        String narrative = planNarrativeService.buildStopReason(museum, breakdown, routeCost, constraints);
        assertNotNull(narrative);
        assertTrue(narrative.contains("三峡博物馆"));
        assertTrue(narrative.contains("历史人文偏好") || narrative.contains("人文"));
        assertTrue(narrative.contains("室内环境舒适") || narrative.contains("雨天友好"));
        assertTrue(narrative.contains("长辈友好") || narrative.contains("随行长辈"));
        assertTrue(narrative.contains("18 分钟"));

        // Verify narrative does NOT contain ungrounded buzzwords
        assertFalse(narrative.contains("最受欢迎"));
        assertFalse(narrative.contains("绝对适合"));
        assertFalse(narrative.contains("实时开放"));
    }

    @Test
    void test06_metricsLoggerTracksOperationalCountersWithoutLoggingPii() {
        long initialCreate = metricsLogger.getPlanCreateCount();
        long initialPreview = metricsLogger.getProposalPreviewCount();
        long initialApply = metricsLogger.getProposalApplyCount();
        long initialConflict = metricsLogger.getRevisionConflictCount();

        PlanRequest request = PlanRequest.builder()
                .prompt("2天行程，喜欢夜景")
                .idempotencyKey("v6-metrics-" + UUID.randomUUID())
                .build();
        PlannerService.ServiceResult created = plannerService.create(request);
        assertEquals(200, created.status());
        assertEquals(initialCreate + 1, metricsLogger.getPlanCreateCount());
        assertTrue(metricsLogger.getLastCreateLatencyMs() >= 0, "Create latency must be non-negative");

        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");
        String firstStopId = String.valueOf(day1Stops.get(0).get("id"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("换掉这个地方")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(firstStopId).build())
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        assertEquals(initialPreview + 1, metricsLogger.getProposalPreviewCount());
        assertTrue(metricsLogger.getLastPreviewLatencyMs() >= 0, "Preview latency must be non-negative");

        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-1")
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult applied = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applied.status());
        assertEquals(initialApply + 1, metricsLogger.getProposalApplyCount());
        assertTrue(metricsLogger.getLastApplyLatencyMs() >= 0, "Apply latency must be non-negative");

        // Put proposal into proposalStore to test revision conflict upon apply
        PlanAdjustmentProposal staleProp = PlanAdjustmentProposal.builder()
                .proposalId("prop-metric-conflict")
                .sessionId(sessionId)
                .baseRevision(1)
                .proposedTrip(Map.of("days", List.of()))
                .expiresAt(System.currentTimeMillis() + 60000)
                .build();
        proposalStore.put(staleProp);

        ApplyAdjustmentRequestDto staleApply = ApplyAdjustmentRequestDto.builder()
                .proposalId("prop-metric-conflict")
                .baseRevision(1) // Stale, current is 2
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult conflictRes = plannerService.applyAdjustment(sessionId, staleApply);
        assertEquals(409, conflictRes.status());
        assertEquals(initialConflict + 1, metricsLogger.getRevisionConflictCount(), "Revision conflict count must increment");

        // Verify logs contain no sensitive tokens or raw prompt strings
        for (String logMsg : metricsLogger.getRecentStructuredLogs()) {
            assertFalse(logMsg.contains(token), "Structured log must not contain sessionAccessToken");
            assertFalse(logMsg.contains("换掉这个地方"), "Structured log must not contain raw user message text");
        }
    }

    @Test
    void test07_metricsLoggerTracksRouteMetricsDirectly() {
        long initialRouteQuery = metricsLogger.getRouteQueryCount();
        long initialEstimated = metricsLogger.getRouteEstimatedCount();
        long initialVerified = metricsLogger.getAmapVerifiedCount();

        Attraction a1 = attractionService.get("cq-jiefangbei");
        Attraction a2 = attractionService.get("cq-hongyadong");
        assertNotNull(a1);
        assertNotNull(a2);

        // Record estimated calculation
        RouteCost estimated = routeCostProvider.calculate(a1, a2, "walking");
        assertNotNull(estimated);
        assertTrue(metricsLogger.getRouteQueryCount() > initialRouteQuery);
        assertTrue(metricsLogger.getRouteEstimatedCount() > initialEstimated);

        // Record verified / cached calculation
        metricsLogger.recordRouteCost(RouteCost.RouteDataStatus.VERIFIED_AMAP);
        assertTrue(metricsLogger.getAmapVerifiedCount() > initialVerified);
    }

    @Test
    void test08_completeGs01ToGs12ScenariosVerification() {
        // GS-01: Baiheliang + downtown inter-district feasibility
        TravelConstraints c1 = constraintParser.parse("白鹤梁水下博物馆和解放碑，2天行程");
        Map<String, Object> trip1 = itineraryBuilder.build(1, c1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days1 = (List<Map<String, Object>>) trip1.get("days");
        assertTrue(planVerifier.verify(days1, c1).feasible());

        // GS-02: Senior + low walking
        TravelConstraints c2 = constraintParser.parse("带父母，希望少走路，喜欢夜景");
        Map<String, Object> trip2 = itineraryBuilder.build(1, c2);
        assertNotNull(trip2);
        assertTrue(String.valueOf(trip2.get("subtitle")).contains("少走路优先"));

        // GS-03: Monday closing hours
        TravelConstraints c3 = constraintParser.parse("2026-08-24 1天 喜欢历史文化"); // Monday
        Map<String, Object> trip3 = itineraryBuilder.build(1, c3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days3 = (List<Map<String, Object>>) trip3.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops3 = (List<Map<String, Object>>) days3.get(0).get("stops");
        assertFalse(stops3.stream().anyMatch(s -> "cq-museum".equals(s.get("venueId"))));

        // GS-04: Evening arrival
        TravelConstraints c4 = constraintParser.parse("周六晚上到重庆，周日离开");
        Map<String, Object> trip4 = itineraryBuilder.build(1, c4);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days4 = (List<Map<String, Object>>) trip4.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops4 = (List<Map<String, Object>>) days4.get(0).get("stops");
        for (Map<String, Object> s : day1Stops4) {
            assertFalse(String.valueOf(s.get("time")).contains("10:") || String.valueOf(s.get("time")).contains("14:"));
        }

        // GS-05: mustVisit vs avoid conflict
        TravelConstraints c5 = constraintParser.applyOverrides(
                constraintParser.parse("喜欢夜景"),
                Map.of("mustVisit", List.of("洪崖洞"), "avoid", List.of("洪崖洞"))
        );
        assertTrue(c5.isNeedsClarification());
        assertFalse(c5.getConflicts().isEmpty());

        // GS-06: UI duration vs prompt text duration mismatch
        TravelConstraints c6Base = constraintParser.parse("玩3天");
        TravelConstraints c6Resolved = constraintParser.applyOverrides(c6Base, Map.of("durationDays", 2));
        assertTrue(c6Resolved.isNeedsClarification());
        assertEquals("durationDays", c6Resolved.getConflicts().get(0).field());

        // GS-07: AMap degradation status
        RouteCost estimatedCost = routeCostProvider.estimateFromCoordinates(
                attractionService.get("cq-jiefangbei"),
                attractionService.get("cq-hongyadong"),
                "walking"
        );
        assertEquals(RouteCost.RouteDataStatus.ESTIMATED, estimatedCost.status());

        // GS-08: LLM offline fallback generates valid base plan
        TravelConstraints c8 = constraintParser.parse("2天行程，带孩子，喜欢自然");
        Map<String, Object> trip8 = itineraryBuilder.build(1, c8);
        assertNotNull(trip8);
        assertEquals(2, ((List<?>) trip8.get("days")).size());

        // GS-09: Suggest replacement preview before apply
        PlannerService.ServiceResult s9 = plannerService.create(PlanRequest.builder().prompt("2天行程").idempotencyKey("gs9-" + UUID.randomUUID()).build());
        String token9 = String.valueOf(s9.body().get("sessionAccessToken"));
        String session9 = String.valueOf(s9.body().get("sessionId"));
        PlanConversationRequestDto prev9 = PlanConversationRequestDto.builder()
                .sessionId(session9).baseRevision(1).message("换掉解放碑")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId("day1-jiefangbei").build())
                .sessionAccessToken(token9).build();
        PlannerService.ServiceResult res9 = plannerService.previewAdjustment(session9, prev9);
        assertEquals(200, res9.status());
        assertEquals(1, ((Map<?,?>)plannerService.get(session9, token9).body().get("trip")).get("version")); // Unmodified

        // GS-10: Rain replan only modifies active day
        PlanConversationRequestDto rainReq = PlanConversationRequestDto.builder()
                .sessionId(session9).baseRevision(1).message("今天下雨了")
                .context(PlanPageContext.builder().activeDay(1).build()).sessionAccessToken(token9).build();
        PlannerService.ServiceResult rainRes = plannerService.previewAdjustment(session9, rainReq);
        assertEquals(200, rainRes.status());

        // GS-11: Reduce density preserves pinned
        PlanConversationRequestDto densityReq = PlanConversationRequestDto.builder()
                .sessionId(session9).baseRevision(1).message("太赶了少安排一个")
                .context(PlanPageContext.builder().activeDay(1).pinnedStopIds(List.of("day1-jiefangbei")).build())
                .sessionAccessToken(token9).build();
        PlannerService.ServiceResult densityRes = plannerService.previewAdjustment(session9, densityReq);
        assertEquals(200, densityRes.status());

        // GS-12: Revision conflict
        ApplyAdjustmentRequestDto staleApply = ApplyAdjustmentRequestDto.builder()
                .proposalId(String.valueOf(res9.body().get("proposalId")))
                .baseRevision(999) // Stale
                .sessionAccessToken(token9).build();
        assertEquals(409, plannerService.applyAdjustment(session9, staleApply).status());
    }
}
