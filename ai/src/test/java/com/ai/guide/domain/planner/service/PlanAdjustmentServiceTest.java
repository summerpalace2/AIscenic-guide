package com.ai.guide.domain.planner.service;





import com.ai.guide.domain.user.model.User;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.api.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.api.PlanConversationRequestDto;
import com.ai.guide.domain.planner.api.PlanRequest;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.PlanAdjustmentProposal;
import com.ai.guide.domain.planner.model.PlanPageContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    @AfterEach
    void tearDown() {
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
                assertFalse((time.contains("18:") || time.contains("19:") || time.contains("20:"))
                                && bestTime.contains("17:00"),
                        "晚间时段不能安排 17:00 闭馆景点");
            }
        }
    }

    @Test
    void explicitSourceAndDestinationGenerateExactReplacementPreview() {
        PlannerService.ServiceResult created = createTestSession("2天行程，喜欢城市和人文");
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
    void explicitReplacementCanBeAppliedAfterUserAcknowledgesWarning() {
        PlannerService.ServiceResult created = createTestSession("2天行程，喜欢城市和人文");
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
        assertEquals(200, applied.status());
        assertEquals(2, applied.body().get("currentVersion"));
        assertEquals(true, applied.body().get("forced"));
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
        PlanConversationRequestDto externalPoiReq = PlanConversationRequestDto.builder()
                .sessionId("test-session")
                .baseRevision(1)
                .message("加上东方明珠电视塔")
                .build();

        PlanAdjustmentIntent intent = intentClassifier.classify(externalPoiReq.getMessage(), new PlanPageContext());
        assertEquals(ConversationIntentType.CLARIFICATION, intent.getType());
        assertTrue(intent.getClarificationQuestion().contains("24 大核心景点"));
        assertTrue(intent.getClarificationQuestion().contains("加强版能力"));
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
}
