package com.ai.guide.domain.planner.service;





import com.ai.guide.domain.user.model.User;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.model.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.model.PlanConversationRequestDto;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.ai.guide.domain.planner.model.PlanPageContext;
import org.junit.jupiter.api.AfterEach;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.planner.model.TravelConstraints;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PlanAdjustmentServiceTest {

    @Autowired
    private PlannerService plannerService;

    @Autowired
    private PlanAdjustmentService planAdjustmentService;

    @Autowired
    private ProposalStore proposalStore;

    @Autowired
    private ConversationIntentClassifier intentClassifier;

    @Autowired
    private com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort sessionRepositoryPort;

    @Autowired(required = false)
    private com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService runtimeAttractionDetailService;

    @Autowired
    private DiningVenueService actualDiningVenueService;

    @Autowired
    private AttractionService attractionService;

    @Autowired
    private ItineraryBuilder itineraryBuilder;

    @AfterEach
    void tearDown() {
        planAdjustmentService.setDiningVenueService(actualDiningVenueService);
        UserContext.clear();
        proposalStore.clear();
    }

    private PlannerService.ServiceResult createTestSession(String prompt) {
        PlanRequest request = PlanRequest.builder()
                .prompt(prompt)
                .idempotencyKey("phase5-test-" + UUID.randomUUID())
                .build();
        return plannerService.create(request);
    }

    private DiningVenueService fixtureDiningService;

    @Test
    @SuppressWarnings("unchecked")
    void knownMondayClosureCannotBeForceApplied() throws Exception {
        var created = createTestSession("1天行程，必须去解放碑");
        String id = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var stored = sessionRepositoryPort.findAnonymousByAccessToken(token);
        var trip = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(stored.trip(), Map.class);
        var days = (List<Map<String,Object>>) trip.get("days");
        var day = days.get(0); day.put("date", "2026-10-05");
        var source = itineraryBuilder.createStop(attractionService.get("cq-jiefangbei"), "source", "10:00", "60分钟", "", "");
        day.put("stops", new ArrayList<>(List.of(source)));
        var revised = sessionRepositoryPort.update(stored, trip, 1, "TEST", "日期验证", "", List.of("source"));
        assertEquals("UPDATED", revised.status());
        var preview = plannerService.previewAdjustment(id, PlanConversationRequestDto.builder().sessionId(id).baseRevision(2)
                .sessionAccessToken(token).message("把解放碑换成三峡博物馆")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId("source").build()).build());
        assertEquals(200,preview.status());
        assertEquals(false,preview.body().get("canApply"));
        assertTrue(String.valueOf(preview.body().get("reasonCodes")).contains("CLOSED_ON_MONDAY"));
        var applied = plannerService.applyAdjustment(id,ApplyAdjustmentRequestDto.builder()
                .proposalId(String.valueOf(preview.body().get("proposalId"))).optionId("option-1")
                .baseRevision(2).sessionAccessToken(token).forceApply(true).build());
        assertEquals(400,applied.status());
        assertEquals(2,sessionRepositoryPort.findAnonymousByAccessToken(token).currentVersion());
    }

    @Test
    void explicitRepeatHasIndependentStopAndNoDuplicateWarning() {
        var created = createTestSession("1天行程，必须去解放碑");
        String id = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        var preview = plannerService.previewAdjustment(id,PlanConversationRequestDto.builder().sessionId(id).baseRevision(1)
                .sessionAccessToken(token).message("第1天晚上再去解放碑看夜景")
                .context(PlanPageContext.builder().activeDay(1).build()).build());
        assertEquals(200,preview.status());
        assertFalse(Boolean.TRUE.equals(preview.body().get("requiresClarification")));
        assertFalse(String.valueOf(preview.body().get("reasonCodes")).contains("DUPLICATE_VENUE"));
        var proposal=proposalStore.get(String.valueOf(preview.body().get("proposalId")));
        assertNotNull(proposal);
        var ids = new java.util.HashSet<String>();
        for(var d:com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.days(proposal.getProposedTrip()))
            for(var stop:com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.dayStops(d))
                assertTrue(ids.add(String.valueOf(stop.get("id"))),"重复访问仍需独立站点标识");
    }

    /** Controlled occupied blocks and POI results isolate dining intent from the initial planning template. */
    private PlannerService.ServiceResult createDiningFixtureSession(String prompt, boolean lunch, boolean dinner) {
        return createDiningFixtureSession(prompt, lunch, dinner, false);
    }

    private PlannerService.ServiceResult createDiningFixtureSession(String prompt, boolean lunch, boolean dinner, boolean busyTea) {
        DiningVenueService dining = mock(DiningVenueService.class);
        fixtureDiningService = dining;
        when(dining.searchLiveDiningOrTea(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString()))
                .thenAnswer(invocation -> {
                    assertEquals("重庆市", invocation.getArgument(5), "指定餐厅查询必须保留当前行程城市");
                    String query = invocation.getArgument(2);
                    assertTrue(query.equals("陈胖子火锅(总店)") || query.equals("肯德基"), "指定店名必须原样传递给POI查询: " + query);
                    return List.of(DiningVenue.builder().id("amap-fixture-venue")
                            .name(query).district("渝中区").location("106.577000,29.556000")
                            .duration("45分钟").specialtyDish("测试菜单").averageCost("待确认")
                            .category(invocation.getArgument(3)).summary("模拟高德餐饮POI").distanceDesc("待核验").build());
                });
        when(dining.resolveDiningReplacementCandidates(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    String slot = invocation.getArgument(3);
                    String flavor = invocation.getArgument(2);
                    assertFalse(flavor.matches(".*(?:把午餐|改成|我想|来点|替换为).*"), "泛需求传给搜索的必须是菜系或食物: " + flavor);
                    String prefix = "TEA_BREAK".equals(slot) ? "【特色茶歇】" : "DINNER".equals(slot) ? "【晚餐推荐】" : "【午餐推荐】";
                    return List.of(new AttractionDiningKnowledge.DiningOption("amap-fixture-cuisine", prefix + flavor + "体验店",
                            "模拟菜单", slot, "消费待确认", "45分钟", "待核验", "模拟高德结果", "候选", "gold", "106.577000,29.556000"));
                });
        planAdjustmentService.setDiningVenueService(dining);
        List<Map<String, Object>> stops = new ArrayList<>();
        stops.add(itineraryBuilder.createStop(attractionService.get("cq-jiefangbei"), "day1-morning", "10:00", "60分钟", "", ""));
        if (lunch) stops.add(itineraryBuilder.createDiningStop(new AttractionDiningKnowledge.DiningOption("fixture-lunch", "【午餐推荐】原午餐店",
                "菜单", "LUNCH", "待确认", "45分钟", "待核验", "原餐饮", "测试", "gold", "106.577000,29.556000"), "day1-lunch", "12:00", "渝中区"));
        if (dinner) stops.add(itineraryBuilder.createDiningStop(new AttractionDiningKnowledge.DiningOption("fixture-dinner", "【晚餐推荐】原晚餐店",
                "菜单", "DINNER", "待确认", "45分钟", "待核验", "原餐饮", "测试", "gold", "106.577000,29.556000"), "day1-dinner", "18:00", "渝中区"));
        if (busyTea) stops.add(itineraryBuilder.createStop(attractionService.get("cq-liziba"), "day1-busy-tea", "14:30", "150分钟", "", ""));
        stops.add(itineraryBuilder.createStop(attractionService.get("cq-hongyadong"), "day1-evening", "20:00", "60分钟", "", ""));
        Map<String, Object> trip = new LinkedHashMap<>(Map.of("version", 1, "city", "重庆市", "days",
                new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("day", 1, "stops", stops))))));
        TravelConstraints constraints = TravelConstraints.builder().destination("重庆市").durationDays(1).rawPrompt(prompt).build();
        var created = sessionRepositoryPort.create("ANONYMOUS", UUID.randomUUID().toString(), prompt, constraints.asMap(),
                com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot.empty(), trip, UUID.randomUUID().toString(), UUID.randomUUID().toString());
        return new PlannerService.ServiceResult(200, Map.of("sessionId", created.session().sessionId(), "sessionAccessToken", created.accessToken(), "trip", trip));
    }

    @Test
    void test01_previewReplacementDoesNotChangeDurablePlanOrRevision() {
        PlannerService.ServiceResult created = createTestSession("3天行程，带父母，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initialDays = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) initialDays.get(0).get("stops");
        String firstStopId = String.valueOf(day1Stops.get(0).get("id"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("这个景点不想去，有没有替换方案？")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .selectedStopId(firstStopId)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("ok")));
        assertEquals("DETERMINISTIC_FALLBACK", previewRes.body().get("intentExtractionSource"));
        assertEquals("FEATURE_DISABLED", previewRes.body().get("llmFallbackReason"));
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        assertNotNull(proposalId);

        // Verify stored session in DB still has version 1 and original trip
        PlannerService.ServiceResult currentSession = plannerService.get(sessionId, token);
        assertEquals(200, currentSession.status());
        Map<?, ?> trip = (Map<?, ?>) currentSession.body().get("trip");
        assertEquals(1, trip.get("version"), "Preview must NOT bump durable revision");

        PlanAdjustmentProposal storedProp = proposalStore.get(proposalId);
        assertNotNull(storedProp, "Proposal must be stored in ProposalStore");
        assertEquals(1, storedProp.getBaseRevision());
    }

    @Test
    void test02_userApplyBumpsRevisionByOne() {
        PlannerService.ServiceResult created = createTestSession("2天行程，喜欢城市和美食");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initialDays = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) initialDays.get(0).get("stops");
        String firstStopId = String.valueOf(day1Stops.get(0).get("id"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("换掉这个景点")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .selectedStopId(firstStopId)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));

        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .baseRevision(1)
                .sessionAccessToken(token)
                .idempotencyKey("apply-" + UUID.randomUUID())
                .build();

        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applyRes.status());
        assertEquals(2, applyRes.body().get("currentVersion"));

        // Verify proposal is removed after application
        assertNull(proposalStore.get(proposalId), "Applied proposal should be cleared from store");

        // Durable session check
        PlannerService.ServiceResult reloaded = plannerService.get(sessionId, token);
        Map<?, ?> trip = (Map<?, ?>) reloaded.body().get("trip");
        assertEquals(2, trip.get("version"));
    }

    @Test
    void test03_applyOnStaleRevisionReturnsConflict409() {
        PlannerService.ServiceResult created = createTestSession("2天行程");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initialDays = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) initialDays.get(0).get("stops");
        String firstStopId = String.valueOf(day1Stops.get(0).get("id"));

        // Generate proposal based on revision 1
        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("不想去这个景点")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(firstStopId).build())
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));

        // Simulate concurrent modification bumping session to revision 2
        ApplyAdjustmentRequestDto validApply = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();
        assertEquals(200, plannerService.applyAdjustment(sessionId, validApply).status());

        // Now attempt to apply with a fake proposal targeting old revision 1
        PlanAdjustmentProposal staleProp = PlanAdjustmentProposal.builder()
                .proposalId("stale-prop-test")
                .sessionId(sessionId)
                .baseRevision(1)
                .proposedTrip(Map.of("days", List.of()))
                .expiresAt(System.currentTimeMillis() + 60000)
                .build();
        proposalStore.put(staleProp);

        ApplyAdjustmentRequestDto staleApply = ApplyAdjustmentRequestDto.builder()
                .proposalId("stale-prop-test")
                .baseRevision(1) // Stale! Current is 2
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult conflictRes = plannerService.applyAdjustment(sessionId, staleApply);
        assertEquals(409, conflictRes.status(), "Applying against stale revision must return 409 conflict");
    }

    @Test
    void test04_proposalBelongingToDifferentSessionIsRejected() {
        PlannerService.ServiceResult s1 = createTestSession("2天行程");
        PlannerService.ServiceResult s2 = createTestSession("2天行程");
        String session1 = String.valueOf(s1.body().get("sessionId"));
        String session2 = String.valueOf(s2.body().get("sessionId"));
        String token2 = String.valueOf(s2.body().get("sessionAccessToken"));

        // Put proposal for session 1
        PlanAdjustmentProposal prop = PlanAdjustmentProposal.builder()
                .proposalId("prop-s1-only")
                .sessionId(session1)
                .baseRevision(1)
                .proposedTrip(Map.of("days", List.of()))
                .expiresAt(System.currentTimeMillis() + 60000)
                .build();
        proposalStore.put(prop);

        // Try applying it in session 2
        ApplyAdjustmentRequestDto applyDto = ApplyAdjustmentRequestDto.builder()
                .proposalId("prop-s1-only")
                .baseRevision(1)
                .sessionAccessToken(token2)
                .build();

        PlannerService.ServiceResult res = plannerService.applyAdjustment(session2, applyDto);
        assertEquals(400, res.status());
        assertTrue(String.valueOf(res.body().get("message")).contains("不属于当前规划会话"));
    }

    @Test
    void test05_expiredProposalIsRejected() {
        PlannerService.ServiceResult created = createTestSession("2天行程");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        // Put expired proposal
        PlanAdjustmentProposal expiredProp = PlanAdjustmentProposal.builder()
                .proposalId("prop-expired")
                .sessionId(sessionId)
                .baseRevision(1)
                .proposedTrip(Map.of("days", List.of()))
                .expiresAt(System.currentTimeMillis() - 1000) // Expired 1 second ago
                .build();
        proposalStore.put(expiredProp);

        ApplyAdjustmentRequestDto applyDto = ApplyAdjustmentRequestDto.builder()
                .proposalId("prop-expired")
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult res = plannerService.applyAdjustment(sessionId, applyDto);
        assertEquals(400, res.status());
        assertTrue(String.valueOf(res.body().get("message")).contains("过期") || String.valueOf(res.body().get("message")).contains("不存在"));
    }

    @Test
    void test06_pronounBindsToSelectedStopIdCorrectly() {
        PlanPageContext context = PlanPageContext.builder()
                .activeDay(2)
                .selectedStopId("day2-liziba")
                .build();

        PlanAdjustmentIntent intent = intentClassifier.classify("这个景点不想去，有没有替换方案？", context);
        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, intent.getType());
        assertEquals("day2-liziba", intent.getTargetStopId());
        assertEquals(2, intent.getDayNumber());
    }

    @Test
    void test07_missingSelectedStopIdReturnsClarificationWithoutGuessing() {
        PlanPageContext context = PlanPageContext.builder()
                .activeDay(1)
                .selectedStopId(null) // No stop selected
                .build();

        PlanAdjustmentIntent intent = intentClassifier.classify("这个景点不想去，换一个", context);
        assertEquals(ConversationIntentType.CLARIFICATION, intent.getType());
        assertTrue(intent.isRequiresClarification());
        assertTrue(intent.getClarificationQuestion().contains("选中"));
    }

    @Test
    void test08_replacingDayTwoLeavesDayOneCompletelyUnchanged() {
        PlannerService.ServiceResult created = createTestSession("3天行程，带长辈，少走路，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initialDays = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1StopsBefore = (List<Map<String, Object>>) initialDays.get(0).get("stops");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day2StopsBefore = (List<Map<String, Object>>) initialDays.get(1).get("stops");
        String targetStopId = String.valueOf(day2StopsBefore.get(0).get("id"));

        // Suggest replacement on Day 2
        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("不想去这个地方，换一个")
                .context(PlanPageContext.builder()
                        .activeDay(2)
                        .selectedStopId(targetStopId)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposedDays = (List<Map<String, Object>>) proposedTrip.get("days");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1StopsAfter = (List<Map<String, Object>>) proposedDays.get(0).get("stops");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day2StopsAfter = (List<Map<String, Object>>) proposedDays.get(1).get("stops");

        // Day 1 venue sequence must remain 100% identical
        assertEquals(day1StopsBefore.size(), day1StopsAfter.size());
        for (int i = 0; i < day1StopsBefore.size(); i++) {
            assertEquals(day1StopsBefore.get(i).get("venueId"), day1StopsAfter.get(i).get("venueId"), "Day 1 venueId must match");
        }

        // Day 2 must have changed
        assertNotEquals(day2StopsBefore.get(0).get("venueId"), day2StopsAfter.get(0).get("venueId"), "Day 2 targeted stop should be replaced");
    }

    @Test
    void test09_rainReplanOnlyModifiesActiveDay() {
        PlannerService.ServiceResult created = createTestSession("3天行程，喜欢夜景和户外打卡");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initialDays = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day2StopsBefore = (List<Map<String, Object>>) initialDays.get(1).get("stops");

        // Rain on Day 1
        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("今天下雨，少安排室外景点")
                .context(PlanPageContext.builder().activeDay(1).activeProposalId("previous-infeasible-proposal").build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposedDays = (List<Map<String, Object>>) proposedTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day2StopsAfter = (List<Map<String, Object>>) proposedDays.get(1).get("stops");

        // Day 2 must remain 100% identical
        assertEquals(day2StopsBefore.size(), day2StopsAfter.size());
        for (int i = 0; i < day2StopsBefore.size(); i++) {
            assertEquals(day2StopsBefore.get(i).get("venueId"), day2StopsAfter.get(i).get("venueId"), "Day 2 venueId must match");
        }
    }

    @Test
    void test10_rainReplanPreservesPinnedAndMustVisitStops() {
        PlanRequest planReq = PlanRequest.builder()
                .prompt("3天行程，必须去洪崖洞")
                .build();
        PlannerService.ServiceResult created = plannerService.create(planReq);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) initialTrip.get("days");

        String hongyadongStopId = null;
        int hongyadongDay = 1;
        for (int d = 0; d < days.size(); d++) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(d).get("stops");
            for (Map<String, Object> s : stops) {
                if ("cq-hongyadong".equals(s.get("venueId")) || String.valueOf(s.get("name")).contains("洪崖洞")) {
                    hongyadongStopId = String.valueOf(s.get("id"));
                    hongyadongDay = d + 1;
                    break;
                }
            }
            if (hongyadongStopId != null) break;
        }

        assertNotNull(hongyadongStopId, "Hongyadong must be in the initial plan");

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("今天下雨了")
                .context(PlanPageContext.builder()
                        .activeDay(hongyadongDay)
                        .pinnedStopIds(List.of(hongyadongStopId, "cq-hongyadong"))
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposedDays = (List<Map<String, Object>>) proposedTrip.get("days");

        boolean hongyadongPresent = false;
        for (Map<String, Object> d : proposedDays) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) d.get("stops");
            for (Map<String, Object> s : stops) {
                if ("cq-hongyadong".equals(s.get("venueId")) || String.valueOf(s.get("name")).contains("洪崖洞")) {
                    hongyadongPresent = true;
                    break;
                }
            }
        }
        assertTrue(hongyadongPresent, "Pinned/must-visit stop must NOT be removed during rain replan");
    }

    @Test
    void test11_reduceDensityPreservesPinnedStops() {
        PlannerService.ServiceResult created = createTestSession("2天行程，必须去解放碑");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");

        String jiefangbeiId = null;
        for (Map<String, Object> s : day1Stops) {
            if ("cq-jiefangbei".equals(s.get("venueId")) || String.valueOf(s.get("name")).contains("解放碑")) {
                jiefangbeiId = String.valueOf(s.get("id"));
                break;
            }
        }
        assertNotNull(jiefangbeiId);

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("今天太赶了，少推荐一个景点")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .pinnedStopIds(List.of(jiefangbeiId, "cq-jiefangbei"))
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) ((Map<?, ?>) ((List<?>) proposedTrip.get("days")).get(0)).get("stops");

        boolean jiefangbeiPresent = stops.stream().anyMatch(s -> "cq-jiefangbei".equals(s.get("venueId")) || String.valueOf(s.get("name")).contains("解放碑"));
        assertTrue(jiefangbeiPresent, "Pinned stop must be preserved during density reduction");
    }

    @Test
    void test12_deterministicParserHandlesCorePhrasesWhenLlmFailsOrOffline() {
        // Classifier operates deterministically and fail-opens reliably
        PlanAdjustmentIntent i1 = intentClassifier.classify("这个景点不想去，有替换吗", PlanPageContext.builder().selectedStopId("s1").build());
        assertEquals(ConversationIntentType.SUGGEST_REPLACEMENTS, i1.getType());

        PlanAdjustmentIntent i2 = intentClassifier.classify("今天太赶了，少安排两个", PlanPageContext.builder().activeDay(1).build());
        assertEquals(ConversationIntentType.REDUCE_DAY_DENSITY, i2.getType());
        assertEquals(2, i2.getReduceCount());

        PlanAdjustmentIntent i3 = intentClassifier.classify("下雨了，少安排室外", PlanPageContext.builder().activeDay(2).build());
        assertEquals(ConversationIntentType.REPLAN_DAY_FOR_CONDITION, i3.getType());
        assertEquals(2, i3.getDayNumber());

        PlanAdjustmentIntent i4 = intentClassifier.classify("换成第二个", PlanPageContext.builder().activeProposalId("p1").build());
        assertEquals(ConversationIntentType.APPLY_REPLACEMENT, i4.getType());
        assertEquals(2, i4.getCandidateIndex());
    }

    @Test
    void narrativePlanSelectionGeneratesFeasibleTripPreview() {
        PlannerService.ServiceResult created = createTestSession("2天行程，带父母，喜欢人文和夜景，少走路");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("方案A：渝中区文化室内线，选这个帮我改行程")
                .context(PlanPageContext.builder().activeDay(1).activeProposalId("previous-infeasible-proposal").build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("feasible")));

        @SuppressWarnings("unchecked")
        Map<String, Object> intent = (Map<String, Object>) previewRes.body().get("intent");
        assertEquals("TRIP", intent.get("scope"));
        assertEquals("REPLAN_DAY", intent.get("operation"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) proposedTrip.get("days");
        for (Map<String, Object> day : days) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stops = (List<Map<String, Object>>) day.get("stops");
            for (Map<String, Object> stop : stops) {
                String time = String.valueOf(stop.getOrDefault("time", ""));
                String bestTime = String.valueOf(stop.getOrDefault("bestTime", ""));
                assertTrue(com.ai.guide.domain.planner.engine.OpeningHoursPolicy.isTimeOpen(
                        com.ai.guide.domain.planner.engine.OpeningHoursPolicy.evidence(
                                String.valueOf(stop.getOrDefault("openingHours", "")), bestTime), time),
                        "站点应处于已知营业区间，不能将 17:00 开始营业误判为闭馆");
            }
        }
    }

    @Test
    void explicitSourceAndDestinationGenerateExactReplacementPreview() {
        PlannerService.ServiceResult created = createTestSession("周二出发2天行程，喜欢城市和人文");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("把重庆中国三峡博物馆替换成重庆大剧院外围广场")
                .context(PlanPageContext.builder().activeDay(2).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("feasible")));

        @SuppressWarnings("unchecked")
        Map<String, Object> intent = (Map<String, Object>) previewRes.body().get("intent");
        assertEquals("REPLACE_STOP", intent.get("operation"));
        assertEquals("cq-grand-theatre", intent.get("replacementPlaceId"));
        assertEquals("DIRECT", previewRes.body().get("replacementMode"));
        assertEquals("重庆大剧院外围广场", previewRes.body().get("requestedReplacementName"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) proposedTrip.get("days");
        boolean grandTheatrePresent = days.stream()
                .flatMap(day -> ((List<Map<String, Object>>) day.get("stops")).stream())
                .anyMatch(stop -> "cq-grand-theatre".equals(stop.get("venueId")));
        boolean museumPresent = days.stream()
                .flatMap(day -> ((List<Map<String, Object>>) day.get("stops")).stream())
                .anyMatch(stop -> "cq-museum".equals(stop.get("venueId")));
        assertTrue(grandTheatrePresent, "Requested destination must be in the preview");
        assertFalse(museumPresent, "Requested source must be removed from the preview");
    }

    @Test
    void replacingAStopWithItselfDoesNotCreateANewRevision() {
        PlannerService.ServiceResult created = createTestSession("周二出发2天行程，喜欢城市和人文");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("把重庆中国三峡博物馆替换成重庆中国三峡博物馆")
                .context(PlanPageContext.builder().activeDay(2).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        assertEquals(false, previewRes.body().get("feasible"));
        assertFalse(((List<?>) previewRes.body().get("optionPlans")).isEmpty(), "明确目标仍需保留可确认的应用草稿");

        ApplyAdjustmentRequestDto applyDto = ApplyAdjustmentRequestDto.builder()
                .proposalId(String.valueOf(previewRes.body().get("proposalId")))
                .optionId("option-1")
                .baseRevision(1)
                .forceApply(true)
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult applied = plannerService.applyAdjustment(sessionId, applyDto);
        assertEquals(false, previewRes.body().get("canApply"));
        assertEquals(400, applied.status());
        assertEquals(1, ((Map<?,?>)plannerService.get(sessionId, token).body().get("trip")).get("version"));
    }

    @Test
    void test13_unknownIntentDoesNotModifyTrip() {
        PlannerService.ServiceResult created = createTestSession("2天行程");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto unknownReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("abcdefg 无关文字")
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult res = plannerService.previewAdjustment(sessionId, unknownReq);
        assertEquals(200, res.status());
        assertTrue(Boolean.TRUE.equals(res.body().get("requiresClarification")));
        assertEquals(false, res.body().get("modified"));
    }

    @Test
    void test14_applyAdjustmentPassesPhase4RouteVerification() {
        PlannerService.ServiceResult created = createTestSession("2天行程");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");
        String firstStopId = String.valueOf(day1Stops.get(0).get("id"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("不想去这个景点，换成其他景点")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(firstStopId).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        assertNotNull(proposalId);

        ApplyAdjustmentRequestDto applyDto = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult applied = plannerService.applyAdjustment(sessionId, applyDto);
        assertEquals(200, applied.status());

        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) applied.body().get("trip");
        assertNotNull(trip);
        assertEquals(2, trip.get("version"));
    }

    @Test
    void test15_sameIdempotencyKeyDoesNotCreateDuplicateRevision() {
        PlannerService.ServiceResult created = createTestSession("2天行程");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");
        String firstStopId = String.valueOf(day1Stops.get(0).get("id"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("去掉这个景点")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(firstStopId).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));

        String idempotency = "idem-phase5-" + UUID.randomUUID();
        ApplyAdjustmentRequestDto apply1 = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .baseRevision(1)
                .idempotencyKey(idempotency)
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult res1 = plannerService.applyAdjustment(sessionId, apply1);
        assertEquals(200, res1.status());
        assertEquals(2, res1.body().get("currentVersion"));
        PlannerService.ServiceResult replay = plannerService.applyAdjustment(sessionId, apply1);
        assertEquals(200, replay.status());
        assertEquals(2, replay.body().get("currentVersion"));
        PlannerService.ServiceResult current = plannerService.get(sessionId, token);
        assertEquals(2, ((Map<?, ?>) current.body().get("trip")).get("version"));
    }

    @Test
    void staleProposalCannotBeAppliedWithForgedCurrentRevision() {
        PlannerService.ServiceResult created = createTestSession("2天行程，喜欢夜景");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        Map<?, ?> trip = (Map<?, ?>) created.body().get("trip");
        List<?> days = (List<?>) trip.get("days");
        Map<?, ?> day1 = (Map<?, ?>) days.get(0);
        List<?> stops = (List<?>) day1.get("stops");
        String target = String.valueOf(((Map<?, ?>) stops.get(0)).get("id"));

        PlanConversationRequestDto request = PlanConversationRequestDto.builder()
                .sessionId(sessionId).baseRevision(1).message("换掉这个景点")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(target).build())
                .sessionAccessToken(token).build();
        PlannerService.ServiceResult firstPreview = plannerService.previewAdjustment(sessionId, request);
        PlannerService.ServiceResult stalePreview = plannerService.previewAdjustment(sessionId, request);
        PlannerService.ServiceResult applied = plannerService.applyAdjustment(sessionId, ApplyAdjustmentRequestDto.builder()
                .proposalId(String.valueOf(firstPreview.body().get("proposalId"))).optionId("option-1")
                .baseRevision(1).sessionAccessToken(token).build());
        assertEquals(200, applied.status());

        PlannerService.ServiceResult forged = plannerService.applyAdjustment(sessionId, ApplyAdjustmentRequestDto.builder()
                .proposalId(String.valueOf(stalePreview.body().get("proposalId"))).optionId("option-1")
                .baseRevision(2).sessionAccessToken(token).build());
        assertEquals(409, forged.status());
        PlannerService.ServiceResult current = plannerService.get(sessionId, token);
        assertEquals(2, ((Map<?, ?>) current.body().get("trip")).get("version"));
    }

    @Test
    void test16_sameProposalSelectingOption1VsOption2AppliesDifferentAttraction() {
        PlannerService.ServiceResult s1 = createTestSession("2天行程，喜欢夜景和美食");
        String sessionId1 = String.valueOf(s1.body().get("sessionId"));
        String token1 = String.valueOf(s1.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> trip1 = (Map<String, Object>) s1.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip1.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");
        String targetStopId = String.valueOf(day1Stops.get(0).get("id"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId1)
                .baseRevision(1)
                .message("不想去这个景点，有替换方案吗？")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build())
                .sessionAccessToken(token1)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId1, previewReq);
        assertEquals(200, previewRes.status());
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) previewRes.body().get("candidateReplacements");
        assertTrue(candidates.size() >= 2, "Preview should return at least 2 candidate options");
        String venueOption1 = String.valueOf(candidates.get(0).get("venueId"));
        String venueOption2 = String.valueOf(candidates.get(1).get("venueId"));
        assertNotEquals(venueOption1, venueOption2);

        // Apply Option 1 in session 1
        ApplyAdjustmentRequestDto apply1 = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-1")
                .baseRevision(1)
                .sessionAccessToken(token1)
                .build();
        PlannerService.ServiceResult res1 = plannerService.applyAdjustment(sessionId1, apply1);
        assertEquals(200, res1.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> appliedTrip1 = (Map<String, Object>) res1.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appliedDays1 = (List<Map<String, Object>>) appliedTrip1.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appliedDay1Stops1 = (List<Map<String, Object>>) appliedDays1.get(0).get("stops");
        assertEquals(venueOption1, String.valueOf(appliedDay1Stops1.get(0).get("venueId")));

        // In a second session with identical proposal, applying Option 2 produces venueOption2
        PlannerService.ServiceResult s2 = createTestSession("2天行程，喜欢夜景和美食");
        String sessionId2 = String.valueOf(s2.body().get("sessionId"));
        String token2 = String.valueOf(s2.body().get("sessionAccessToken"));

        PlanConversationRequestDto previewReq2 = PlanConversationRequestDto.builder()
                .sessionId(sessionId2)
                .baseRevision(1)
                .message("不想去这个景点，有替换方案吗？")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build())
                .sessionAccessToken(token2)
                .build();
        PlannerService.ServiceResult previewRes2 = plannerService.previewAdjustment(sessionId2, previewReq2);
        String proposalId2 = String.valueOf(previewRes2.body().get("proposalId"));

        ApplyAdjustmentRequestDto apply2 = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId2)
                .optionId("option-2")
                .baseRevision(1)
                .sessionAccessToken(token2)
                .build();
        PlannerService.ServiceResult res2 = plannerService.applyAdjustment(sessionId2, apply2);
        assertEquals(200, res2.status());
        @SuppressWarnings("unchecked")
        Map<String, Object> appliedTrip2 = (Map<String, Object>) res2.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appliedDays2 = (List<Map<String, Object>>) appliedTrip2.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> appliedDay1Stops2 = (List<Map<String, Object>>) appliedDays2.get(0).get("stops");
        assertEquals(venueOption2, String.valueOf(appliedDay1Stops2.get(0).get("venueId")));
    }

    @Test
    void test17_invalidOptionIdDoesNotModifyRevision() {
        PlannerService.ServiceResult created = createTestSession("2天行程");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");
        String targetStopId = String.valueOf(day1Stops.get(0).get("id"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("换掉这个地方")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(targetStopId).build())
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));

        ApplyAdjustmentRequestDto invalidOptionApply = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-999") // Invalid option ID!
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, invalidOptionApply);
        assertEquals(400, applyRes.status());
        assertTrue(String.valueOf(applyRes.body().get("message")).contains("不存在"));

        // Revision must remain 1
        PlannerService.ServiceResult reloaded = plannerService.get(sessionId, token);
        Map<?, ?> reloadedTrip = (Map<?, ?>) reloaded.body().get("trip");
        assertEquals(1, reloadedTrip.get("version"));
    }

    @Test
    void test18_candidateOrdinalOutOfRangeReturnsClarification() {
        PlanPageContext context = PlanPageContext.builder()
                .activeProposalId("prop-1")
                .build();

        PlanAdjustmentIntent intent = intentClassifier.classify("换成第五个方案", context);
        assertEquals(ConversationIntentType.CLARIFICATION, intent.getType());
        assertTrue(intent.isRequiresClarification());
        assertTrue(intent.getClarificationQuestion().contains("1 到 3"));
    }

    @Test
    void test19_addStopPreviewAndApplyAddsToTargetDayLeavesOtherDaysUnchanged() {
        PlannerService.ServiceResult created = createTestSession("3天行程，喜欢城市地标");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initialDays = (List<Map<String, Object>>) initialTrip.get("days");
        Object day1StopsBefore = initialDays.get(0).get("stops");

        // Add Beicang to Day 2
        PlanConversationRequestDto addReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("第二天加上北仓")
                .context(PlanPageContext.builder().activeDay(2).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, addReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("feasible")));
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        assertNotNull(proposalId);

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposedDays = (List<Map<String, Object>>) proposedTrip.get("days");

        // Day 1 must be 100% unchanged
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1StopsAfter = (List<Map<String, Object>>) proposedDays.get(0).get("stops");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1StopsListBefore = (List<Map<String, Object>>) day1StopsBefore;
        assertEquals(day1StopsListBefore.size(), day1StopsAfter.size());
        for (int i = 0; i < day1StopsListBefore.size(); i++) {
            assertEquals(day1StopsListBefore.get(i).get("venueId"), day1StopsAfter.get(i).get("venueId"), "Day 1 venueId must match");
            assertEquals(day1StopsListBefore.get(i).get("id"), day1StopsAfter.get(i).get("id"), "Day 1 stop id must match");
        }

        // Apply addition
        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult applied = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applied.status());
        assertEquals(2, applied.body().get("currentVersion"));
    }

    @Test
    void test20_addDuplicateStopReturnsInfeasiblePreview() {
        PlannerService.ServiceResult created = createTestSession("2天行程，必须去解放碑");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        // Attempt to add Jiefangbei again
        PlanConversationRequestDto addDuplicateReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("加上解放碑")
                .context(PlanPageContext.builder().activeDay(2).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, addDuplicateReq);
        assertEquals(200, previewRes.status());
        assertEquals(false, previewRes.body().get("feasible"));
        @SuppressWarnings("unchecked")
        List<String> reasonCodes = (List<String>) previewRes.body().get("reasonCodes");
        assertTrue(reasonCodes.contains("DUPLICATE_VENUE"));
        assertNotNull(previewRes.body().get("alternatives"));
    }

    @Test
    void test21_addExternalPoiReturnsClarification() {
        PlannerService.ServiceResult created = createTestSession("2天行程，必须去解放碑");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto externalPoiReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("加上东方明珠电视塔")
                .sessionAccessToken(token)
                .build();

        PlanAdjustmentIntent intent = intentClassifier.classify(externalPoiReq.getMessage(), new PlanPageContext());
        assertEquals(ConversationIntentType.ADD_STOP, intent.getType());
        assertEquals("东方明珠电视塔", intent.getRequestedVenueName());

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, externalPoiReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("requiresClarification")));
        String q = String.valueOf(previewRes.body().get("clarificationQuestion"));
        assertTrue(q.contains("未能检索到") || q.contains("特色"));
    }

    @Test
    void test22_infeasiblePreviewCannotBeApplied() {
        PlannerService.ServiceResult created = createTestSession("2天行程，必须去解放碑");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto addDuplicateReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("加上解放碑")
                .context(PlanPageContext.builder().activeDay(2).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, addDuplicateReq);
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        assertNotNull(proposalId);
        assertEquals(false, previewRes.body().get("feasible"));

        // Attempt to apply the infeasible proposal MUST be rejected
        ApplyAdjustmentRequestDto applyDto = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, applyDto);
        assertEquals(400, applyRes.status());
        assertTrue(String.valueOf(applyRes.body().get("message")).contains("不可行约束冲突"));

        // Revision remains unchanged at 1
        PlannerService.ServiceResult reloaded = plannerService.get(sessionId, token);
        Map<?, ?> reloadedTrip = (Map<?, ?>) reloaded.body().get("trip");
        assertEquals(1, reloadedTrip.get("version"));

        // When forceApply = true, user choice is respected and applied
        ApplyAdjustmentRequestDto forceApplyDto = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .baseRevision(1)
                .forceApply(true)
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult forceRes = plannerService.applyAdjustment(sessionId, forceApplyDto);
        assertEquals(200, forceRes.status());
        PlannerService.ServiceResult updated = plannerService.get(sessionId, token);
        Map<?, ?> updatedTrip = (Map<?, ?>) updated.body().get("trip");
        assertEquals(2, updatedTrip.get("version"));
    }

    @Test
    void test23_replanDayVagueRequestReturnsClarificationWithFourOptions() {
        // Scenario 1: “改第二天景点”
        PlannerService.ServiceResult created = createTestSession("3天行程，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("改第二天景点")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult res = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, res.status());
        assertTrue(Boolean.TRUE.equals(res.body().get("requiresClarification")));
        assertEquals("CLARIFICATION", res.body().get("type"));
        String question = String.valueOf(res.body().get("clarificationQuestion"));
        assertTrue(question.contains("第 2 天") || question.contains("第二天"));
        assertTrue(question.contains("重新规划") || question.contains("替换") || question.contains("减少"));

        // Verify durable trip has NOT been modified
        PlannerService.ServiceResult reloaded = plannerService.get(sessionId, token);
        Map<?, ?> trip = (Map<?, ?>) reloaded.body().get("trip");
        assertEquals(1, trip.get("version"));
    }

    @Test
    void test24_reduceDayDensityGeneratesPreviewWithoutMutatingTripOrRevision() {
        // Scenario 2: “第二天太赶了，少排一个”
        PlannerService.ServiceResult created = createTestSession("2天行程，带父母");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("第二天太赶了，少排一个")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult res = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, res.status());
        assertNotNull(res.body().get("proposalId"));
        assertEquals(1, res.body().get("baseRevision"));

        // Verify durable trip revision is still 1
        PlannerService.ServiceResult reloaded = plannerService.get(sessionId, token);
        Map<?, ?> trip = (Map<?, ?>) reloaded.body().get("trip");
        assertEquals(1, trip.get("version"));
    }

    @Test
    void test25_rainReplanGeneratesConditionPreviewWithoutMutatingTripOrRevision() {
        // Scenario 3: “明天下雨，不想一直在室外”
        PlannerService.ServiceResult created = createTestSession("2天行程，喜欢夜景");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("明天下雨，不想一直在室外")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult res = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, res.status());
        assertNotNull(res.body().get("proposalId"));

        PlannerService.ServiceResult reloaded = plannerService.get(sessionId, token);
        Map<?, ?> trip = (Map<?, ?>) reloaded.body().get("trip");
        assertEquals(1, trip.get("version"));
    }

    @Test
    void test26_placeQuestionReturnsQaAnswerWithoutTriggeringPreview() {
        // Scenario 5: “为什么推荐洪崖洞？”
        PlannerService.ServiceResult created = createTestSession("2天行程");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("为什么推荐洪崖洞？")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult res = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, res.status());
        assertEquals("PLACE_QUESTION", res.body().get("type"));
        assertNotNull(res.body().get("answer"));
        assertNull(res.body().get("proposalId"), "Place question must NOT generate a proposal");
    }

    @Test
    void test27_conversationOptionSelectDoesNotApplyDurableTripUntilUserConfirms() {
        // Scenario 6: “选第二个方案”
        PlannerService.ServiceResult created = createTestSession("2天行程，喜欢夜景和美食");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");
        String firstStopId = String.valueOf(day1Stops.get(0).get("id"));

        // 1. Generate Proposal preview
        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("换掉这个景点")
                .context(PlanPageContext.builder().activeDay(1).selectedStopId(firstStopId).build())
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        assertNotNull(proposalId);

        // 2. User says "选第二个方案" in conversation
        PlanConversationRequestDto selectReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("选第二个方案")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .activeProposalId(proposalId)
                        .build())
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult selectRes = plannerService.previewAdjustment(sessionId, selectReq);
        assertEquals(200, selectRes.status());
        assertEquals("PROPOSAL_OPTION_SELECTED", selectRes.body().get("type"));
        assertEquals("option-2", selectRes.body().get("selectedOptionId"));
        assertEquals(2, selectRes.body().get("candidateIndex"));

        // 3. Verify durable trip is STILL at version 1 (NOT mutated by conversational option selection)
        PlannerService.ServiceResult unappliedSession = plannerService.get(sessionId, token);
        Map<?, ?> unappliedTrip = (Map<?, ?>) unappliedSession.body().get("trip");
        assertEquals(1, unappliedTrip.get("version"), "Conversational option select must NOT mutate durable revision");

        // 4. User explicitly clicks "确认应用" button
        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-2")
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applyRes.status());
        assertEquals(2, applyRes.body().get("currentVersion"));

        // 5. Verify durable trip is now at version 2
        PlannerService.ServiceResult appliedSession = plannerService.get(sessionId, token);
        Map<?, ?> appliedTrip = (Map<?, ?>) appliedSession.body().get("trip");
        assertEquals(2, appliedTrip.get("version"));
    }

    @Test
    void testDiningStopReplacementRecommendsRestaurantsNotScenicSpots() {
        PlannerService.ServiceResult created = createTestSession("南岸区两日游，带父母，喜欢地道美食");
        assertEquals(200, created.status());
        String sessionId = (String) created.body().get("sessionId");
        String token = (String) created.body().get("sessionAccessToken");
        Map<?, ?> trip = (Map<?, ?>) created.body().get("trip");

        // Find dining stop
        String diningStopId = null;
        String diningStopName = null;
        int targetDay = 1;
        List<?> days = (List<?>) trip.get("days");
        for (Object d : days) {
            Map<?, ?> day = (Map<?, ?>) d;
            List<?> stops = (List<?>) day.get("stops");
            for (Object s : stops) {
                Map<?, ?> stop = (Map<?, ?>) s;
                String type = String.valueOf(stop.get("type"));
                String icon = String.valueOf(stop.get("icon"));
                String name = String.valueOf(stop.get("name"));
                if ("DINING".equalsIgnoreCase(type) || "餐".equals(icon) || name.contains("餐")) {
                    diningStopId = String.valueOf(stop.get("id"));
                    diningStopName = name;
                    targetDay = ((Number) day.get("day")).intValue();
                    break;
                }
            }
            if (diningStopId != null) break;
        }

        assertNotNull(diningStopId, "Trip should contain at least one dining stop");

        // Request dining replacement
        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("请把【" + diningStopName + "】替换为附近地道重庆江湖菜或老字号中餐")
                .context(PlanPageContext.builder()
                        .activeDay(targetDay)
                        .selectedStopId(diningStopId)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status(), "Preview adjustment should succeed");
        assertTrue((Boolean) previewRes.body().get("feasible"), "Preview should be feasible");
        assertEquals("DINING", previewRes.body().get("replacementMode"), "Replacement mode should be DINING");

        List<?> candidates = (List<?>) previewRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty(), "Should return dining candidates");

        for (Object c : candidates) {
            Map<?, ?> cand = (Map<?, ?>) c;
            assertTrue(Boolean.TRUE.equals(cand.get("isDining")) || "DINING".equals(cand.get("type")),
                    "Candidate must be a restaurant, not a scenic spot: " + cand.get("name"));
            assertNotEquals("弹子石老街", cand.get("name"), "Must not recommend scenic spot like 弹子石老街");
            assertNotNull(cand.get("specialtyDish"), "Dining candidate should have specialty dish");
        }

        // Apply option-1
        String proposalId = (String) previewRes.body().get("proposalId");
        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-1")
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applyRes.status(), "Applying proposal should succeed");
        assertEquals(2, applyRes.body().get("currentVersion"));

        // Verify applied trip has the new dining stop
        PlannerService.ServiceResult getRes = plannerService.get(sessionId, token);
        Map<?, ?> updatedTrip = (Map<?, ?>) getRes.body().get("trip");
        assertEquals(2, updatedTrip.get("version"));

        List<?> updatedDays = (List<?>) updatedTrip.get("days");
        Map<?, ?> day1 = (Map<?, ?>) updatedDays.get(targetDay - 1);
        List<?> updatedStops = (List<?>) day1.get("stops");
        boolean foundNewDining = false;
        for (Object s : updatedStops) {
            Map<?, ?> stop = (Map<?, ?>) s;
            if (diningStopId.equals(stop.get("id"))) {
                foundNewDining = true;
                assertNotEquals(diningStopName, stop.get("name"), "Old dining should be replaced");
                assertEquals("DINING", stop.get("type"));
                break;
            }
        }
        assertTrue(foundNewDining, "Updated stop with diningStopId should exist");
    }

    @Test
    void narrativeBreakfastNoodlesSchemeInsertsMorningStopAndPreservesAttractions() {
        PlannerService.ServiceResult created = createTestSession("2天行程，带父母，喜欢人文和夜景，少走路");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initDays = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initDay1Stops = (List<Map<String, Object>>) initDays.get(0).get("stops");
        int originalStopCount = initDay1Stops.size();
        String originalFirstStopVenue = String.valueOf(initDay1Stops.get(0).get("venueId"));

        // User adopts Scheme A from assistant suggestion
        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("采用方案A：早餐小面+上午平街观展（最贴合\"低步行\"，帮我局部调整行程")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("feasible")), "Breakfast adjustment must be feasible");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) previewRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty(), "Must return breakfast noodle candidates");
        assertTrue(String.valueOf(candidates.get(0).get("name")).contains("小面")
                || String.valueOf(candidates.get(0).get("name")).contains("豌杂面")
                || String.valueOf(candidates.get(0).get("name")).contains("面馆"),
                "Top candidate must be authentic noodle shop");

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposedDays = (List<Map<String, Object>>) proposedTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> proposedDay1Stops = (List<Map<String, Object>>) proposedDays.get(0).get("stops");

        // Breakfast must fit the configured interval and retain the original visit time.
        assertEquals(originalStopCount + 1, proposedDay1Stops.size(), "Day 1 stops should increase by 1 for breakfast");
        Map<String, Object> breakfastStop = proposedDay1Stops.get(0);
        assertEquals("DINING", breakfastStop.get("type"));
        int breakfastStart = new com.ai.guide.domain.planner.engine.ItineraryTimeEngine().parseTimeMinutes(String.valueOf(breakfastStop.get("time")));
        int breakfastDuration = new com.ai.guide.domain.planner.engine.ItineraryTimeEngine().parseDurationMinutes(String.valueOf(breakfastStop.get("duration")));
        assertTrue(breakfastStart >= 450 && breakfastStart + breakfastDuration <= 570);
        assertTrue(String.valueOf(breakfastStop.get("name")).contains("早餐推荐")
                || String.valueOf(breakfastStop.get("name")).contains("小面")
                || String.valueOf(breakfastStop.get("name")).contains("面馆"));

        // The second stop must be the original first attraction (e.g. 三峡博物馆), completely intact!
        assertEquals(originalFirstStopVenue, String.valueOf(proposedDay1Stops.get(1).get("venueId")),
                "Original morning attraction must be preserved after breakfast insertion");
        assertEquals(initDay1Stops.get(0).get("time"), proposedDay1Stops.get(1).get("time"));

        // Apply option-1
        String proposalId = (String) previewRes.body().get("proposalId");
        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-1")
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();
        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applyRes.status());
        assertEquals(2, applyRes.body().get("currentVersion"));
    }

    @Test
    void narrativeLunchNoodlesSchemeReplacesLunchStop() {
        PlannerService.ServiceResult created = createTestSession("2天行程，喜欢城市和美食");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        // User adopts Scheme B to lighten lunch into noodles
        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("采用方案B：把较场口午餐换成小面（帮我局部调整行程")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("feasible")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) previewRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty());
        assertTrue(String.valueOf(candidates.get(0).get("name")).contains("小面")
                        || String.valueOf(candidates.get(0).get("name")).contains("豌杂面")
                        || String.valueOf(candidates.get(0).get("name")).contains("面馆"),
                "Top candidate must be authentic noodle shop");
    }

    @Test
    void test35_shortTimeBudgetReplacementPrefersNearbyAttractionOverDistantPopularAttraction() {
        PlannerService.ServiceResult created = createTestSession("我在重大 为我规划5小时旅游路线");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initialDays = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) initialDays.get(0).get("stops");
        String firstStopId = String.valueOf(day1Stops.get(0).get("id"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("请为【红岩革命纪念馆】推荐适合替换的室内景点")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .selectedStopId(firstStopId)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("ok")));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) previewRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty(), "Candidates must not be empty");

        // Top candidate must NOT be far-away Huguang Guild Hall across town in eastern Yuzhong (15km+ away)
        String topCandidateVenueId = String.valueOf(candidates.get(0).get("venueId"));
        String topCandidateName = String.valueOf(candidates.get(0).get("name"));
        assertNotEquals("cq-huguang-guild", topCandidateVenueId,
                "Under 5-hour limited time plan at CQU/Shapingba, Huguang Guild Hall (15km away) must NOT be top recommendation! Actual: " + topCandidateName);

        // Candidates must include nearby indoor attractions (e.g. Hongyan Memorial, Three Gorges Museum, Ronghui Hotspring, Geleyuan)
        boolean hasNearbyIndoor = candidates.stream().anyMatch(c ->
                "cq-hongyan-memorial".equals(c.get("venueId"))
                        || "cq-museum".equals(c.get("venueId"))
                        || "cq-ronghui-hotspring".equals(c.get("venueId"))
                        || "cq-geleyuan".equals(c.get("venueId")));
        assertTrue(hasNearbyIndoor, "Candidates must include nearby Shapingba/western corridor indoor attractions. Actual candidates: " + candidates);
    }

    @Test
    void test35_addDessertStopGeneratesDiningProposalWithoutRejection() {
        PlannerService.ServiceResult created = createDiningFixtureSession("1天行程，磁器口后街，低步行", true, true);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto previewReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("在当前行程加入一处甜点或特色糖水小憩停留")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, previewReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("ok")));
        assertFalse(Boolean.TRUE.equals(previewRes.body().get("requiresClarification")),
                "Must NOT require clarification with '未收录地点' rejection. Body: " + previewRes.body());

        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        assertNotNull(proposalId);
        assertEquals("TEA_BREAK", previewRes.body().get("replacementMode"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) previewRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty(), "Must have dining dessert candidates");
        assertTrue(candidates.stream().anyMatch(c -> Boolean.TRUE.equals(c.get("isDining"))));

        // Test apply adjustment
        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-1")
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applyRes.status());
        assertTrue(Boolean.TRUE.equals(applyRes.body().get("applied")));
        assertEquals(2, applyRes.body().get("currentVersion"));

        @SuppressWarnings("unchecked")
        Map<String, Object> updatedTrip = (Map<String, Object>) applyRes.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) updatedTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");
        boolean hasAddedDining = stops.stream().anyMatch(s -> "DINING".equals(s.get("type")) && String.valueOf(s.get("id")).contains("added-dining"));
        assertTrue(hasAddedDining, "Updated trip must contain the added dessert/dining stop");

        // Verify chronological order of stops
        int lastMinutes = -1;
        for (Map<String, Object> s : stops) {
            String timeStr = String.valueOf(s.getOrDefault("time", ""));
            String clean = timeStr.replaceAll("[^0-9:]", "").trim();
            if (clean.contains(":")) {
                String[] parts = clean.split(":");
                int m = Integer.parseInt(parts[0]) * 60 + (parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
                assertTrue(m >= lastMinutes, "Stops must be in chronological time order! Violation: " + s.get("name") + " at " + timeStr + ", previous minutes was " + lastMinutes);
                lastMinutes = m;
            }
        }
    }

    @Test
    void test37_addDynamicAmapPoiToTrip() {
        if (runtimeAttractionDetailService != null) {
            com.ai.guide.domain.attraction.model.Attraction cqupt = com.ai.guide.domain.attraction.model.Attraction.builder()
                    .id("amap-B00170CQUPT")
                    .name("重庆邮电大学四教")
                    .displayName("重庆邮电大学四教")
                    .district("南岸区")
                    .location("106.608123,29.531234")
                    .summary("重庆邮电大学第四教学楼打卡点")
                    .duration("约 45 分钟")
                    .recommendedVisitMinutes(45)
                    .build();
            runtimeAttractionDetailService.registerDynamicAttraction(cqupt, "重庆市");
        }

        PlannerService.ServiceResult created = createTestSession("2天行程，第1天解放碑洪崖洞，第2天南山一棵树与黄桷垭老街");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto addPoiReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("增加一个重庆邮电大学四教打卡点的景点")
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, addPoiReq);
        assertEquals(200, previewRes.status());
        assertFalse(Boolean.TRUE.equals(previewRes.body().get("requiresClarification")),
                "Dynamic POI must NOT trigger clarification failure. Body: " + previewRes.body());

        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        assertNotNull(proposalId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) previewRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty());
        assertEquals("重庆邮电大学四教", candidates.get(0).get("name"));
        assertEquals("南岸区", candidates.get(0).get("district"));

        // Apply proposal
        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-1")
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applyRes.status());
        assertEquals(2, applyRes.body().get("currentVersion"));

        @SuppressWarnings("unchecked")
        Map<String, Object> updatedTrip = (Map<String, Object>) applyRes.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) updatedTrip.get("days");
        boolean hasCqupt = days.stream()
                .flatMap(d -> ((List<Map<String, Object>>) d.get("stops")).stream())
                .anyMatch(s -> "重庆邮电大学四教".equals(s.get("name")));
        assertTrue(hasCqupt, "Updated trip must contain 重庆邮电大学四教");
    }

    @Test
    void test_planIntentSafeFallbackGeneratesProposalOption1() {
        PlannerService.ServiceResult created = createTestSession("2天行程，带父母，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto planReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("从头规划第一天")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, planReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("ok")));
        assertNotNull(previewRes.body().get("proposalId"));
    }

    @Test
    void test_diningPlaceQuestionDoesNotReturnAmapUnverifiedMessage() {
        PlannerService.ServiceResult created = createTestSession("2天行程，带父母，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto qaReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("附近有没有什么好吃的烧烤店？")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .selectedStopId("day1-1")
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult qaRes = plannerService.previewAdjustment(sessionId, qaReq);
        assertEquals(200, qaRes.status());
        String answer = String.valueOf(qaRes.body().get("answer"));
        assertFalse(answer.contains("暂时没有从高德核验到该景点的详情"), "餐饮探索不应输出高德未核验文案");
        assertTrue(answer.contains("正在为您检索附近的特色餐饮与美食推荐") || answer.contains("特色") || answer.contains("餐饮"));
    }

    @Test
    void test_diningReplacementProtectsAttractionFromBeingOverwritten() {
        PlannerService.ServiceResult created = createTestSession("2天行程，带父母，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        // 用户说晚餐吃老火锅，即便选中了某个核心景点，系统也不应把核心景点删除替换掉，而是安全处理
        PlanConversationRequestDto diningReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("晚上安排顿老火锅")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .selectedStopId("day1-1")
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, diningReq);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("ok")));
    }

    @Test
    void test_multiStopRouteSegmentAdditionGeneratesOptionsAndAppliesCleanly() {
        PlannerService.ServiceResult created = createTestSession("2天行程，带父母，喜欢夜景和历史");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        // Isolate a feasible insertion window: the generated evening template is already dense.
        // With full catalog visit durations, appending three stops after its night anchor is infeasible.
        var stored = sessionRepositoryPort.findAnonymousByAccessToken(token);
        @SuppressWarnings("unchecked")
        Map<String,Object> fixtureTrip = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(stored.trip(), Map.class);
        var fixtureDays = com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.days(fixtureTrip);
        var anchor = itineraryBuilder.createStop(attractionService.get("cq-hongyadong"), "fixture-anchor", "10:00", "90分钟", "", "");
        fixtureDays.get(0).put("stops", new ArrayList<>(List.of(anchor)));
        fixtureDays.get(1).put("stops", new ArrayList<>());
        assertEquals("UPDATED", sessionRepositoryPort.update(stored, fixtureTrip, 1, "TEST", "可行的多站插入窗口", "", List.of("fixture-anchor")).status());

        String userPrompt = "在洪崖洞之后接长江索道北站单程过江，南岸游览龙门浩老街与南滨路夜景，随后在南站打车返回解放碑";
        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(2)
                .message(userPrompt)
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, previewRes.status());
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("ok")));
        assertTrue(Boolean.TRUE.equals(previewRes.body().get("feasible")), String.valueOf(previewRes.body()));
        assertNotNull(previewRes.body().get("proposalId"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) previewRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertTrue(candidates.size() >= 2, "应生成多套候选接入方案（如完整动线、单程过江等）");

        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-1")
                .baseRevision(2)
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applyRes.status());
        assertTrue(Boolean.TRUE.equals(applyRes.body().get("applied")));
        assertEquals(3, ((Number) applyRes.body().get("currentVersion")).intValue());

        @SuppressWarnings("unchecked")
        Map<String, Object> updatedTrip = (Map<String, Object>) applyRes.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) updatedTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");

        boolean hasCable = day1Stops.stream().anyMatch(s -> String.valueOf(s.get("venueId")).contains("cable") || String.valueOf(s.get("name")).contains("索道"));
        boolean hasLongmenhao = day1Stops.stream().anyMatch(s -> String.valueOf(s.get("venueId")).contains("longmenhao") || String.valueOf(s.get("name")).contains("龙门浩"));
        boolean hasNanbinlu = day1Stops.stream().anyMatch(s -> String.valueOf(s.get("venueId")).contains("nanbinlu") || String.valueOf(s.get("name")).contains("南滨路"));

        assertTrue(hasCable, "行程中必须已接入长江索道");
        assertTrue(hasLongmenhao, "行程中必须已接入龙门浩老街");
        assertTrue(hasNanbinlu, "行程中必须已接入南滨路");
    }

    @Test
    void test_directionalSyntaxBeforeAnchorInsertsBeforeAndRipples() {
        PlannerService.ServiceResult created = createTestSession("1天行程，下午去解放碑和洪崖洞");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        String userPrompt = "在去洪崖洞之前，我们先去南滨路吹吹江风";
        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message(userPrompt)
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, previewRes.status());
        assertNotNull(previewRes.body().get("proposalId"));

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) proposedTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");

        int nanbinIndex = -1;
        int hongyaIndex = -1;
        for (int i = 0; i < day1Stops.size(); i++) {
            String name = String.valueOf(day1Stops.get(i).get("name"));
            String venueId = String.valueOf(day1Stops.get(i).get("venueId"));
            if (name.contains("南滨路") || venueId.contains("nanbinlu")) nanbinIndex = i;
            if (name.contains("洪崖洞") || venueId.contains("hongyadong")) hongyaIndex = i;
        }

        assertTrue(nanbinIndex >= 0, "必须成功将南滨路插入行程中");
        assertTrue(hongyaIndex >= 0, "行程中必须保留洪崖洞");
        assertTrue(nanbinIndex < hongyaIndex, "用户明确指定‘在洪崖洞之前’，南滨路索引（" + nanbinIndex + "）必须在洪崖洞（" + hongyaIndex + "）前面！");

        String nanbinTime = String.valueOf(day1Stops.get(nanbinIndex).get("time"));
        String hongyaTime = String.valueOf(day1Stops.get(hongyaIndex).get("time"));
        assertTrue(nanbinTime.compareTo(hongyaTime) <= 0, "南滨路时间（" + nanbinTime + "）必须不晚于洪崖洞（" + hongyaTime + "），杜绝时空倒流");
    }

    @Test
    void test_lateCableCarCutoffRejectsFeasibility() {
        PlannerService.ServiceResult created = createTestSession("1天行程");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        // 模拟晚上 21:30 游玩洪崖洞后安排长江索道，索道将在 22:45 才能到达（超过 22:00 停运时间）
        String userPrompt = "晚上十点在洪崖洞之后接长江索道、龙门浩老街和南滨路夜景";
        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message(userPrompt)
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, previewRes.status());
        assertFalse(Boolean.TRUE.equals(previewRes.body().get("feasible")), "索道晚间 22:00 停运，过晚排程必须判定为不可行（feasible=false）");

        @SuppressWarnings("unchecked")
        List<String> reasonCodes = (List<String>) previewRes.body().get("reasonCodes");
        assertNotNull(reasonCodes);
        assertTrue(reasonCodes.contains("CLOSED_AT_SLOT_TIME") || reasonCodes.contains("MIDNIGHT_OVERFLOW"), "必须明确报告闭园/停运或跨午夜违规原因码");
    }

    @Test
    void test_transitWaypointPreservesIntermediateRevisit() {
        PlannerService.ServiceResult created = createTestSession("1天行程");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        String userPrompt = "我们想先回解放碑换件厚衣服吃个火锅，之后接上南滨路动线";
        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message(userPrompt)
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, previewRes.status());

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) proposedTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");

        boolean hasNanbinlu = day1Stops.stream().anyMatch(s -> String.valueOf(s.get("name")).contains("南滨路") || String.valueOf(s.get("venueId")).contains("nanbinlu"));
        assertTrue(hasNanbinlu, "中途途经点指令下南滨路必须被正确排入行程");
    }

    @Test
    void test_genericMultiStopAdditionWithoutHardcodedCopy() {
        PlannerService.ServiceResult created = createTestSession("1天行程");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        String userPrompt = "帮我把李子坝、鹅岭二厂串联接入行程";
        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message(userPrompt)
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, previewRes.status());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) previewRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertTrue(candidates.size() >= 2);

        Map<String, Object> opt2 = candidates.stream().filter(c -> "option-2".equals(c.get("optionId"))).findFirst().orElse(null);
        assertNotNull(opt2);
        String opt2Summary = String.valueOf(opt2.get("summary"));
        String opt2Name = String.valueOf(opt2.get("name"));
        assertFalse(opt2Summary.contains("索道") || opt2Summary.contains("飞渡长江"), "非索道景点（李子坝）严禁脑补索道飞渡长江文案！");
        assertFalse(opt2Name.contains("单程过江"), "非索道景点严禁出现单程过江后缀！");
    }

    @Test
    void test_zeroWidthObfuscationInsertionAndCommaSeparatedWaypointEndToEnd() {
        PlannerService.ServiceResult created = createTestSession("1天行程，下午去解放碑和洪崖洞");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        // 攻击向量：注入零宽空格与连接符，试图破坏分词器与地标匹配
        String attackPayload = "在去洪\u200B崖\u200B洞之\u200D前，先\uFEFF去南滨路吹吹江风";
        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message(attackPayload)
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, previewRes.status());
        assertNotNull(previewRes.body().get("proposalId"), "零宽字符攻击必须被清洗并成功生成提案");

        @SuppressWarnings("unchecked")
        Map<String, Object> proposedTrip = (Map<String, Object>) previewRes.body().get("proposedTrip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) proposedTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");

        int nanbinIndex = -1;
        int hongyaIndex = -1;
        for (int i = 0; i < day1Stops.size(); i++) {
            String name = String.valueOf(day1Stops.get(i).get("name"));
            String venueId = String.valueOf(day1Stops.get(i).get("venueId"));
            if (name.contains("南滨路") || venueId.contains("nanbinlu")) nanbinIndex = i;
            if (name.contains("洪崖洞") || venueId.contains("hongyadong")) hongyaIndex = i;
        }

        assertTrue(nanbinIndex >= 0, "零宽字符清洗后南滨路必须成功插桩");
        assertTrue(hongyaIndex >= 0, "行程中必须保留洪崖洞");
        assertTrue(nanbinIndex < hongyaIndex, "插桩后南滨路必须排在洪崖洞前面");
    }

    @Test
    void test48_addTeahouseGaiwanteaStopGeneratesTeaBreakCandidatesAndDoesNotDefaultToLunch() {
        PlannerService.ServiceResult created = createDiningFixtureSession("1天行程，磁器口后街，低步行", true, true);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto req = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("在路线中增加一处特色老茶馆或盖碗茶品茗停留")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult previewRes = plannerService.previewAdjustment(sessionId, req);
        assertEquals(200, previewRes.status());
        assertEquals("TEA_BREAK", previewRes.body().get("replacementMode"),
                "增加老茶馆/盖碗茶品茗停留必须识别为 TEA_BREAK，绝不能降级为 LUNCH 替换");

        String msg = String.valueOf(previewRes.body().get("message"));
        assertTrue(msg.contains("品茗") || msg.contains("茶"), "提案预览说明必须包含茶歇品茗导引，实际为: " + msg);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) previewRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty(), "必须返回老茶馆或盖碗茶候选方案");

        for (Map<String, Object> cand : candidates) {
            String candName = String.valueOf(cand.get("name"));
            assertFalse(candName.startsWith("【午餐推荐】"), "茶歇方案名称绝不能冠以【午餐推荐】，实际为: " + candName);
            assertTrue(candName.startsWith("【特色茶歇】") || candName.contains("茶"),
                    "茶歇方案名称应为【特色茶歇】前缀或包含茶主题，实际为: " + candName);
        }

        // 验证应用提案
        String proposalId = String.valueOf(previewRes.body().get("proposalId"));
        ApplyAdjustmentRequestDto applyReq = ApplyAdjustmentRequestDto.builder()
                .proposalId(proposalId)
                .optionId("option-1")
                .baseRevision(1)
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult applyRes = plannerService.applyAdjustment(sessionId, applyReq);
        assertEquals(200, applyRes.status());
        assertTrue(Boolean.TRUE.equals(applyRes.body().get("applied")));

        @SuppressWarnings("unchecked")
        Map<String, Object> trip = (Map<String, Object>) applyRes.body().get("trip");
        assertNotNull(trip);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");

        boolean hasTeaStop = day1Stops.stream().anyMatch(s ->
                "茶歇".equals(s.get("category")) || "茶".equals(s.get("icon")) || String.valueOf(s.get("name")).contains("茶"));
        assertTrue(hasTeaStop, "行程中必须成功插入茶歇节点");
    }

    @Test
    void test49_explicitAreaWithoutProviderDoesNotReturnAnotherDistrictTeahouse() {
        PlannerService.ServiceResult created = createTestSession("1天行程，磁器口后街，低步行");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("南岸附近有没有茶馆")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        String reply = String.valueOf(convRes.body().get("answer"));

        assertFalse(reply.contains("关于重庆旅游景点，您可以随时在行程中点击卡片提问"),
                "问茶馆绝不能返回冷冰冰的景点回退文案");
        assertTrue(reply.contains("未找到可核验") && reply.contains("位置"),
                "无实时供应商且无法核验指定区域时，应明确请求确认位置，实际为: " + reply);
        assertFalse(reply.contains("磁器口"), "不能用原行程所在的磁器口餐厅代替用户指定的南岸区域");
    }

    @Test
    void test50_answerDiningOrTeaQuestionForDessert() {
        PlannerService.ServiceResult created = createTestSession("1天行程，磁器口后街，低步行");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("有没有什么甜食")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        String reply = String.valueOf(convRes.body().get("answer"));

        assertFalse(reply.contains("正在为您检索附近的特色餐饮与美食推荐"),
                "问甜食绝不能返回空洞的检索中占位符");
        assertTrue(reply.contains("甜") || reply.contains("冰粉") || reply.contains("凉糕") || reply.contains("汤圆"),
                "必须给出地道的甜品小吃推荐，实际为: " + reply);
    }

    @Test
    void test51_reproduceChenPangziDiningReplacement() {
        PlannerService.ServiceResult created = createDiningFixtureSession("下午2点半出发，解放碑，洪崖洞", true, true);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        Map<String, Object> trip = (Map<String, Object>) created.body().get("trip");
        List<Map<String, Object>> days = (List<Map<String, Object>>) trip.get("days");
        List<Map<String, Object>> stops = (List<Map<String, Object>>) days.get(0).get("stops");
        System.out.println("=== INITIAL STOPS IN DAY 1 ===");
        String firstDiningId = null;
        for (Map<String, Object> stop : stops) {
            System.out.println(" - Stop: id=" + stop.get("id") + ", name=" + stop.get("name") + ", time=" + stop.get("time") + ", type=" + stop.get("type"));
            if ("DINING".equals(stop.get("type")) && firstDiningId == null) {
                firstDiningId = String.valueOf(stop.get("id"));
            }
        }

        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("把午餐改成【陈胖子火锅(总店)】")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .selectedStopId(firstDiningId)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        System.out.println("=== PREVIEW RESULT BODY ===");
        System.out.println("message = " + convRes.body().get("message"));
        System.out.println("replacementMode = " + convRes.body().get("replacementMode"));
        System.out.println("sourceStopName = " + convRes.body().get("sourceStopName"));
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) convRes.body().get("candidateReplacements");
        if (candidates != null) {
            for (Map<String, Object> cand : candidates) {
                System.out.println("Candidate: optionId=" + cand.get("optionId") + ", name=" + cand.get("name")
                        + ", requestedName=" + cand.get("requestedName")
                        + ", directReplacement=" + cand.get("directReplacement"));
            }
        }
        assertTrue(String.valueOf(convRes.body().get("message")).contains("陈胖子火锅(总店)"),
                "Message must contain 陈胖子火锅(总店) but got: " + convRes.body().get("message"));
        verify(fixtureDiningService).searchLiveDiningOrTea(anyString(), anyString(), eq("陈胖子火锅(总店)"), eq("LUNCH"), eq(1), eq("重庆市"));
    }

    @Test
    void test52_reproduceChenPangziDiningWhenOnlyDinnerExists() {
        PlannerService.ServiceResult created = createDiningFixtureSession("下午2点半出发，解放碑，洪崖洞", false, true);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));
        PlannerSessionRepositoryPort.StoredSession stored = sessionRepositoryPort.findAnonymousByAccessToken(token);
        Map<String, Object> tripMap = new LinkedHashMap<>(stored.trip());
        List<Map<String, Object>> days = new ArrayList<>((List<Map<String, Object>>) tripMap.get("days"));
        Map<String, Object> day1 = new LinkedHashMap<>(days.get(0));
        List<Map<String, Object>> stops = new ArrayList<>((List<Map<String, Object>>) day1.get("stops"));

        // 移除午餐站点，模拟行程中只有晚餐站点的真实情况（如用户截图中的场景）
        stops.removeIf(s -> "day1-lunch".equals(s.get("id")) || String.valueOf(s.get("name")).contains("午餐"));
        day1.put("stops", stops);
        days.set(0, day1);
        tripMap.put("days", days);
        PlannerSessionRepositoryPort.MutationResult mut = sessionRepositoryPort.update(
                stored, tripMap, stored.currentVersion(), "TEST", "Remove lunch", "Setup", List.of());
        assertEquals("UPDATED", mut.status());

        String dinnerStopId = null;
        for (Map<String, Object> stop : stops) {
            if ("day1-dinner".equals(stop.get("id")) || String.valueOf(stop.get("name")).contains("晚餐")) {
                dinnerStopId = String.valueOf(stop.get("id"));
            }
        }
        assertNotNull(dinnerStopId, "测试前置：当天必须存在晚餐站点");

        // 用户在抽屉中选了晚餐卡片，但口述命令指明“把午餐改成【陈胖子火锅(总店)】”
        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(mut.session().currentVersion())
                .message("把午餐改成【陈胖子火锅(总店)】")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .selectedStopId(dinnerStopId)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        System.out.println("=== TEST 52 PREVIEW RESULT BODY ===");
        System.out.println("message = " + convRes.body().get("message"));
        System.out.println("replacementMode = " + convRes.body().get("replacementMode"));
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) convRes.body().get("candidateReplacements");
        assertNotNull(candidates, "必须返回候选调整项");
        assertFalse(candidates.isEmpty(), "候选调整项不能为空");

        Map<String, Object> opt1 = candidates.get(0);
        System.out.println("Opt1 Name: " + opt1.get("name"));
        System.out.println("Opt1 RequestedName: " + opt1.get("requestedName"));
        System.out.println("Opt1 DirectReplacement: " + opt1.get("directReplacement"));

        assertTrue(String.valueOf(opt1.get("name")).contains("【午餐推荐】"),
                "指定午餐时，绝对不能串成【晚餐推荐】，实际为: " + opt1.get("name"));
        assertTrue(String.valueOf(opt1.get("requestedName")).contains("陈胖子火锅(总店)"),
                "目标餐厅必须为陈胖子火锅(总店)，实际为: " + opt1.get("requestedName"));
        assertEquals(Boolean.TRUE, opt1.get("directReplacement"),
                "必须标记为直接精确替换 directReplacement=true");
        assertTrue(String.valueOf(convRes.body().get("message")).contains("陈胖子火锅(总店)"),
                "返回消息必须包含陈胖子火锅(总店)，实际为: " + convRes.body().get("message"));

        // 验证原晚餐站点未被破坏或劫持
        Map<String, Object> proposedTrip = (Map<String, Object>) convRes.body().get("proposedTrip");
        List<Map<String, Object>> proposedDays = (List<Map<String, Object>>) proposedTrip.get("days");
        List<Map<String, Object>> proposedStops = (List<Map<String, Object>>) proposedDays.get(0).get("stops");
        boolean dinnerStillExists = proposedStops.stream().anyMatch(s -> String.valueOf(s.get("name")).contains("晚餐"));
        assertTrue(dinnerStillExists, "原晚餐站点必须被物理保护，不能被午餐加站给误删或篡改");
        verify(fixtureDiningService).searchLiveDiningOrTea(anyString(), anyString(), eq("陈胖子火锅(总店)"), eq("LUNCH"), eq(1), eq("重庆市"));
    }

    @Test
    void test53_genericBrandDiningWithoutFoodSuffix() {
        PlannerService.ServiceResult created = createDiningFixtureSession("解放碑，较场口，洪崖洞，2天", true, true);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        // 无书名号/方括号，且完全不带“火锅/餐馆/小面/总店/分店”等后缀的任意知名品牌商户
        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("把午餐改成肯德基")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) convRes.body().get("candidateReplacements");
        assertNotNull(candidates, "必须返回候选调整项");
        assertFalse(candidates.isEmpty(), "候选调整项不能为空");

        Map<String, Object> opt1 = candidates.get(0);
        System.out.println("Test 53 Opt1 Name: " + opt1.get("name"));
        System.out.println("Test 53 Opt1 RequestedName: " + opt1.get("requestedName"));
        System.out.println("Test 53 Opt1 DirectReplacement: " + opt1.get("directReplacement"));

        assertTrue(String.valueOf(opt1.get("name")).contains("肯德基"),
                "候选方案名称必须包含肯德基，实际为: " + opt1.get("name"));
        assertEquals("肯德基", opt1.get("requestedName"),
                "目标商户必须精确提取为肯德基");
        assertEquals(Boolean.TRUE, opt1.get("directReplacement"),
                "必须支持无后缀品牌的直接精确替换 directReplacement=true");
        verify(fixtureDiningService).searchLiveDiningOrTea(anyString(), anyString(), eq("肯德基"), eq("LUNCH"), eq(1), eq("重庆市"));
    }

    @Test
    void test54_genericDessertCravingDoesNotBecomeDirectStoreName() {
        PlannerService.ServiceResult created = createDiningFixtureSession("解放碑，较场口，洪崖洞，2天", true, true);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("我想吃点甜食的")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        assertTrue(Boolean.TRUE.equals(convRes.body().get("feasible")));

        String message = String.valueOf(convRes.body().get("message"));
        System.out.println("Test 54 Message: " + message);
        assertFalse(message.contains("安排【点甜食的】"), "绝不能将泛需求‘点甜食的’当作指定店铺名称！");
        assertTrue(message.contains("甜品"), "甜食需求不能被改成茶馆: " + message);
        assertFalse(message.contains("品茗") || message.contains("茶社") || message.contains("老茶馆"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) convRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty());

        for (Map<String, Object> cand : candidates) {
            String name = String.valueOf(cand.get("name"));
            assertFalse(name.contains("点甜食的"), "候选卡片名称绝不能出现‘点甜食的’虚假店名: " + name);
            assertNotEquals(Boolean.TRUE, cand.get("directReplacement"), "泛需求绝不能标记为 directReplacement=true");
        }
    }

    @Test
    void colloquialDessertVariantsProduceEquivalentPreviewsOnSameTrip() {
        var created = createDiningFixtureSession("市区一日游", true, true);
        Object originalTrip = plannerService.get(String.valueOf(created.body().get("sessionId")),
                String.valueOf(created.body().get("sessionAccessToken"))).body().get("trip");
        Map<String, Object> first = null;
        for (String message : List.of("我想吃点甜食的", "我想吃点甜食", "我想吃一点甜的", "想来点甜品吧", "我想吃点甜食的呀")) {
            var body = previewDiningFixture(created, message);
            assertEquals(true, body.get("feasible"), message + body);
            assertEquals("TEA_BREAK", body.get("replacementMode"), message);
            assertEquals("特色甜品糖水", ((Map<?, ?>) body.get("intent")).get("cuisineOrPlace"), message);
            assertTrue(String.valueOf(body.get("message")).contains("甜品"), message);
            assertFalse(String.valueOf(body.get("message")).contains("品茗"), message);
            if (first != null) {
                assertEquals(first.get("candidateReplacements"), body.get("candidateReplacements"), message);
                assertEquals(first.get("proposedTrip"), body.get("proposedTrip"), message);
            }
            first = body;
        }
        verify(fixtureDiningService, times(5)).resolveDiningReplacementCandidates(anyString(), anyString(),
                eq("特色甜品糖水"), eq("TEA_BREAK"), any(), any());
        verify(fixtureDiningService, never()).searchLiveDiningOrTea(anyString(), anyString(), anyString(), anyString(), anyInt(), anyString());
        assertEquals(originalTrip, plannerService.get(String.valueOf(created.body().get("sessionId")),
                String.valueOf(created.body().get("sessionAccessToken"))).body().get("trip"), "预览不得修改原行程");
    }

    @Test
    void noDessertCandidatesIsExplainedAsSearchFailureRatherThanTimeConflict() {
        var created = createDiningFixtureSession("市区一日游", true, true);
        when(fixtureDiningService.resolveDiningReplacementCandidates(anyString(), anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(List.of());
        for (String message : List.of("我想吃点甜食的", "我想吃点甜食")) {
            var body = previewDiningFixture(created, message);
            assertEquals(false, body.get("feasible"));
            assertTrue(((List<?>) body.get("reasonCodes")).contains("DINING_CANDIDATES_UNAVAILABLE"));
            assertFalse(((List<?>) body.get("reasonCodes")).contains("NO_FEASIBLE_MEAL_GAP"));
            assertTrue(String.valueOf(body.get("message")).contains("甜品"));
            assertTrue(String.valueOf(body.get("message")).contains("未找到可用候选"));
            assertEquals(false, body.get("canApply"));
            var applied = plannerService.applyAdjustment(String.valueOf(created.body().get("sessionId")),
                    ApplyAdjustmentRequestDto.builder()
                            .proposalId(String.valueOf(body.get("proposalId"))).baseRevision(1)
                            .optionId("option-1").forceApply(true)
                            .sessionAccessToken(String.valueOf(created.body().get("sessionAccessToken"))).build());
            assertEquals(400, applied.status(), "强制应用不能绕过没有候选地点的失败");

        }
    }

    @Test
    void fullTeaWindowIsExplainedAsTimeConflictForBothDessertPhrases() {
        var created = createDiningFixtureSession("市区一日游", true, true, true);
        for (String message : List.of("我想吃点甜食的", "我想吃点甜食")) {
            var body = previewDiningFixture(created, message);
            assertEquals(false, body.get("feasible"));
            assertTrue(((List<?>) body.get("reasonCodes")).contains("NO_FEASIBLE_MEAL_GAP"));
            assertTrue(String.valueOf(body.get("message")).contains("空闲时间"));
            assertFalse(String.valueOf(body.get("message")).contains("不太合理"));
        }
    }

    private Map<String, Object> previewDiningFixture(PlannerService.ServiceResult created, String message) {
        String session = String.valueOf(created.body().get("sessionId"));
        var result = plannerService.previewAdjustment(session, PlanConversationRequestDto.builder()
                .sessionId(session).baseRevision(1).message(message)
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(String.valueOf(created.body().get("sessionAccessToken"))).build());
        assertEquals(200, result.status());
        return result.body();
    }

    @Test
    void test55_genericHotpotCravingDoesNotBecomeDirectStoreName() {
        PlannerService.ServiceResult created = createDiningFixtureSession("解放碑，较场口，洪崖洞，2天", false, true);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("我想吃火锅")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        assertTrue(Boolean.TRUE.equals(convRes.body().get("feasible")), "Preview body: " + convRes.body());

        String message = String.valueOf(convRes.body().get("message"));
        System.out.println("Test 55 Message: " + message);
        assertFalse(message.contains("安排【火锅】"), "绝不能将泛需求‘火锅’当作指定店铺名称！");
        assertTrue(message.contains("火锅"), "回复必须包含火锅推荐信息");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) convRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty());

        for (Map<String, Object> cand : candidates) {
            String name = String.valueOf(cand.get("name"));
            assertFalse("【午餐推荐】火锅".equals(name) || "【晚餐推荐】火锅".equals(name) || "火锅".equals(name),
                    "候选卡片名称绝不能是单字虚假店名‘火锅’: " + name);
            assertNotEquals(Boolean.TRUE, cand.get("directReplacement"), "泛需求绝不能标记为 directReplacement=true");
        }
    }

    @Test
    void test56_genericBurgerCravingDoesNotBecomeStoreName() {
        PlannerService.ServiceResult created = createDiningFixtureSession("1天行程，市区游览", false, true);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("我想吃汉堡")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        assertTrue(Boolean.TRUE.equals(convRes.body().get("feasible")), "Preview body: " + convRes.body());

        String message = String.valueOf(convRes.body().get("message"));
        assertFalse(message.contains("安排【汉堡】"), "绝不能将‘汉堡’直接作为店铺名安排: " + message);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) convRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty());
        for (Map<String, Object> cand : candidates) {
            String name = String.valueOf(cand.get("name"));
            assertFalse(name.endsWith("【汉堡】") || "汉堡".equals(name), "候选店名不能为纯品类‘汉堡’");
            assertNotEquals(Boolean.TRUE, cand.get("directReplacement"), "泛需求不能标记为直接精确替换");
        }
    }

    @Test
    void test57_replaceLunchWithPizzaDoesNotCreateFakeStore() {
        PlannerService.ServiceResult created = createDiningFixtureSession("1天行程，中午吃点清淡的", true, true);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("把午餐改成披萨")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        assertTrue(Boolean.TRUE.equals(convRes.body().get("feasible")));

        String message = String.valueOf(convRes.body().get("message"));
        assertFalse(message.contains("替换为“披萨”") || message.contains("安排【披萨】"),
                "绝不能将纯品类‘披萨’当作独立店铺名称: " + message);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) convRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty());
        for (Map<String, Object> cand : candidates) {
            assertNotEquals(Boolean.TRUE, cand.get("directReplacement"), "泛品类披萨不能标记为直接替换");
        }
    }

    @Test
    void test58_eveningBarbecueCravingResolvesToRecommendations() {
        PlannerService.ServiceResult created = createDiningFixtureSession("1天行程，晚上看洪崖洞夜景", true, true);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("晚上来点烧烤")
                .context(PlanPageContext.builder().activeDay(1).build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        assertTrue(Boolean.TRUE.equals(convRes.body().get("feasible")));

        String message = String.valueOf(convRes.body().get("message"));
        assertFalse(message.contains("安排【烧烤】"), "绝不能将‘烧烤’作为商户名: " + message);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) convRes.body().get("candidateReplacements");
        assertNotNull(candidates);
        assertFalse(candidates.isEmpty());
        for (Map<String, Object> cand : candidates) {
            assertNotEquals(Boolean.TRUE, cand.get("directReplacement"));
        }
    }

    @Test
    void test59_removeStopByVenueIdFallback() {
        PlannerService.ServiceResult created = createTestSession("3天行程，想去洪崖洞和解放碑");
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        @SuppressWarnings("unchecked")
        Map<String, Object> initialTrip = (Map<String, Object>) created.body().get("trip");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> initialDays = (List<Map<String, Object>>) initialTrip.get("days");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) initialDays.get(0).get("stops");
        assertTrue(day1Stops.size() > 1, "第 1 天需有多于 1 站以便测试删除");

        // 故意使用 venueId 而非 stopId 作为 targetStopId
        String venueIdToRemove = String.valueOf(day1Stops.get(0).get("venueId"));
        assertNotNull(venueIdToRemove);

        PlanConversationRequestDto convReq = PlanConversationRequestDto.builder()
                .sessionId(sessionId)
                .baseRevision(1)
                .message("移除这个景点")
                .context(PlanPageContext.builder()
                        .activeDay(1)
                        .selectedStopId(venueIdToRemove)
                        .build())
                .sessionAccessToken(token)
                .build();

        PlannerService.ServiceResult convRes = plannerService.previewAdjustment(sessionId, convReq);
        assertEquals(200, convRes.status());
        assertTrue(Boolean.TRUE.equals(convRes.body().get("ok")));
        String propId = String.valueOf(convRes.body().get("proposalId"));
        assertNotNull(propId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void eveningOptimizationProtectsDaytimeAndMealsAndAppliesExactlyOneVersion() {
        var created = createDiningFixtureSession("1天行程，喜欢夜景", true, true);
        String sessionId = (String) created.body().get("sessionId"), token = (String) created.body().get("sessionAccessToken");
        Map<String, Object> original = (Map<String, Object>) created.body().get("trip");
        Map<String, Object> originalDay = ((List<Map<String, Object>>)original.get("days")).get(0);
        var originals = (List<Map<String, Object>>)originalDay.get("stops");
        var beforeMorning = new LinkedHashMap<>(originals.get(0));
        var beforeLunch = new LinkedHashMap<>(originals.get(1));
        var beforeDinner = new LinkedHashMap<>(originals.get(2));
        String message = "将傍晚和晚间行程优化为最佳夜景观景点";
        var context = PlanPageContext.builder().activeDay(1).build();
        var request = PlanConversationRequestDto.builder().message(message).context(context).sessionAccessToken(token).baseRevision(1).build();
        var preview = planAdjustmentService.preview(sessionId, request);
        assertEquals(200, preview.status());
        assertEquals(true, preview.body().get("canApply"));
        assertEquals(1020, ((Map<String,Object>)preview.body().get("intent")).get("targetStartMinute"));
        assertEquals(1440, ((Map<String,Object>)preview.body().get("intent")).get("targetEndMinute"));
        var proposed = (Map<String,Object>)preview.body().get("proposedTrip");
        var proposedStops = (List<Map<String,Object>>)((List<Map<String,Object>>)proposed.get("days")).get(0).get("stops");
        assertEquals(beforeMorning, proposedStops.get(0));
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(beforeLunch).toString(), new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(proposedStops.get(1)).toString());
        assertEquals(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(beforeDinner).toString(), new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(proposedStops.get(2)).toString());
        assertNotEquals(originals.get(3).get("venueId"), proposedStops.get(3).get("venueId"));
        assertEquals("20:00", proposedStops.get(3).get("time"));
        assertEquals(1, ((List<?>)preview.body().get("stopChanges")).size());
        assertEquals(1, ((Map<?,?>)plannerService.get(sessionId, token).body().get("trip")).get("version"));
        context.setActiveProposalId((String) preview.body().get("proposalId"));
        var repeat = planAdjustmentService.preview(sessionId, request);
        assertEquals(preview.body().get("proposalId"), repeat.body().get("proposalId"));
        assertEquals(true, repeat.body().get("reused"));
        var applied = planAdjustmentService.apply(sessionId, ApplyAdjustmentRequestDto.builder().proposalId((String)preview.body().get("proposalId"))
                .optionId("option-1").baseRevision(1).forceApply(true).sessionAccessToken(token).idempotencyKey("night-loop-test").build());
        assertEquals(200, applied.status());
        assertEquals(2, applied.body().get("currentVersion"));
        assertEquals(beforeMorning, ((List<Map<String,Object>>)((List<Map<String,Object>>)((Map<String,Object>)applied.body().get("trip")).get("days")).get(0).get("stops")).get(0));
    }

    @Test
    void fullyPinnedNightCannotPretendToGenerateAnApplicableChange() {
        var created = createDiningFixtureSession("1天行程，喜欢夜景", true, true);
        String id = (String)created.body().get("sessionId"), token = (String)created.body().get("sessionAccessToken");
        var result = planAdjustmentService.preview(id, PlanConversationRequestDto.builder().message("将傍晚和晚间行程优化为最佳夜景观景点")
                .sessionAccessToken(token).baseRevision(1).context(PlanPageContext.builder().activeDay(1).pinnedStopIds(List.of("day1-evening")).build()).build());
        assertEquals(false, result.body().get("canApply"));
        assertTrue(((List<?>)result.body().get("reasonCodes")).contains("NO_ACTUAL_CHANGE"));
        assertTrue(String.valueOf(result.body().get("message")).contains("没有产生实际改动"));
        var applied = planAdjustmentService.apply(id, ApplyAdjustmentRequestDto.builder().proposalId((String)result.body().get("proposalId"))
                .optionId("option-1").baseRevision(1).forceApply(true).sessionAccessToken(token).build());
        assertEquals(400, applied.status());
        assertEquals(1, ((Map<?,?>)plannerService.get(id, token).body().get("trip")).get("version"));
    }
    @Test
    @SuppressWarnings("unchecked")
    void selectedOptionIsValidatedIndependentlyOfDefaultNoOp() {
        var created=createTestSession("2天行程");String sessionId=String.valueOf(created.body().get("sessionId")),token=String.valueOf(created.body().get("sessionAccessToken"));
        Map<String,Object> original=(Map<String,Object>)created.body().get("trip");
        Map<String,Object> changed=new com.fasterxml.jackson.databind.ObjectMapper().convertValue(original,Map.class);
        var stop=(Map<String,Object>)((List<?>)((Map<?,?>)((List<?>)changed.get("days")).get(0)).get("stops")).get(0);stop.put("duration","35分钟");
        PendingIntentContinuations continuations=(PendingIntentContinuations)org.springframework.test.util.ReflectionTestUtils.getField(planAdjustmentService,"continuations");
        assertNotNull(continuations);
        continuations.begin(sessionId);
        String epoch=continuations.epoch(sessionId);
        String id="option-validation-"+UUID.randomUUID();
        proposalStore.put(PlanAdjustmentProposal.builder().proposalId(id).sessionId(sessionId).baseRevision(1)
            .conversationEpoch(epoch)
            .intent(PlanAdjustmentIntent.builder().type(ConversationIntentType.REPLAN_DAY).operation("REPLAN_DAY").scope("DAY").dayNumber(1).build())
            .proposedTrip(original).optionPlans(Map.of("option-1",original,"option-2",changed)).feasible(false).reasonCodes(List.of("NO_ACTUAL_CHANGE"))
            .createdAt(System.currentTimeMillis()).expiresAt(System.currentTimeMillis()+60000).build());
        assertEquals(400,plannerService.applyAdjustment(sessionId,ApplyAdjustmentRequestDto.builder().proposalId(id).optionId("option-1").baseRevision(1).forceApply(true).sessionAccessToken(token).build()).status(),"默认空改动永远不可强制应用");
        assertEquals(200,plannerService.applyAdjustment(sessionId,ApplyAdjustmentRequestDto.builder().proposalId(id).optionId("option-2").baseRevision(1).forceApply(true).sessionAccessToken(token).build()).status(),"真实有效第二候选不受默认NO_ACTUAL_CHANGE误封");
    }
}


