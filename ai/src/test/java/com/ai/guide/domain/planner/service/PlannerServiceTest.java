package com.ai.guide.domain.planner.service;


import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.planner.api.ApplyAdjustmentRequestDto;
import com.ai.guide.domain.planner.api.PlanConversationRequestDto;
import com.ai.guide.domain.planner.api.PlanRequest;
import com.ai.guide.domain.planner.api.PlannerMutationRequest;
import com.ai.guide.domain.planner.api.ShadowMutationRequest;
import com.ai.guide.domain.planner.api.ShadowPlanRequest;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.api.UserPreferencesPatch;
import com.ai.guide.domain.trip.service.TripService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class PlannerServiceTest {

    @Autowired
    private PlannerService plannerService;

    @Autowired
    private PreferencesService preferencesService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TripService tripService;

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void createsAndReplansDurableJavaSession() {
        PlanRequest request = new PlanRequest();
        request.setPrompt("周六下午到重庆，周日晚上离开，带父母，希望少走路，预算有限，喜欢城市、人文和夜景。");
        request.setIdempotencyKey("planner-test-" + UUID.randomUUID());

        PlannerService.ServiceResult created = plannerService.create(request);
        assertEquals(200, created.status());
        assertTrue(Boolean.TRUE.equals(created.body().get("ok")));
        assertNotNull(created.body().get("sessionId"));
        assertNotNull(created.body().get("sessionAccessToken"));
        Map<?, ?> trip = (Map<?, ?>) created.body().get("trip");
        assertEquals(2, ((java.util.List<?>) trip.get("days")).size());

        PlannerMutationRequest mutation = new PlannerMutationRequest();
        mutation.setTargetStopId("day1-hongyadong");
        mutation.setReason("少走路");
        mutation.setSessionAccessToken(String.valueOf(created.body().get("sessionAccessToken")));
        PlannerService.ServiceResult replanned = plannerService.replan(String.valueOf(created.body().get("sessionId")), mutation);
        assertEquals(200, replanned.status());
        assertEquals("cq-grand-theatre", replanned.body().get("replacementVenueId"));
        Map<?, ?> updatedTrip = (Map<?, ?>) replanned.body().get("trip");
        assertEquals(2, updatedTrip.get("version"));
        assertEquals(2, ((java.util.List<?>) updatedTrip.get("versionHistory")).size());
    }

    @Test
    void guestIdempotencyReplayRequiresTheSameCapability() {
        String key = "planner-guest-replay-" + UUID.randomUUID();
        PlanRequest firstRequest = new PlanRequest();
        firstRequest.setPrompt("喜欢城市和夜景");
        firstRequest.setIdempotencyKey(key);
        PlannerService.ServiceResult first = plannerService.create(firstRequest);
        assertEquals(200, first.status());
        String sessionId = String.valueOf(first.body().get("sessionId"));
        String capability = String.valueOf(first.body().get("sessionAccessToken"));

        PlanRequest replayRequest = new PlanRequest();
        replayRequest.setPrompt("喜欢城市和夜景");
        replayRequest.setIdempotencyKey(key);
        replayRequest.setSessionAccessToken(capability);
        PlannerService.ServiceResult replay = plannerService.create(replayRequest);
        assertEquals(200, replay.status());
        assertEquals(sessionId, replay.body().get("sessionId"));

        PlanRequest missingCapability = new PlanRequest();
        missingCapability.setPrompt("喜欢城市和夜景");
        missingCapability.setIdempotencyKey(key);
        PlannerService.ServiceResult isolated = plannerService.create(missingCapability);
        assertEquals(200, isolated.status());
        assertNotEquals(sessionId, isolated.body().get("sessionId"));

        PlanRequest wrongCapability = new PlanRequest();
        wrongCapability.setPrompt("喜欢城市和夜景");
        wrongCapability.setIdempotencyKey(key);
        wrongCapability.setSessionAccessToken("wrong-capability");
        assertEquals(401, plannerService.create(wrongCapability).status());
    }

    @Test
    void rejectsStalePlannerVersion() {
        PlanRequest request = new PlanRequest();
        request.setPrompt("喜欢城市和夜景");
        request.setIdempotencyKey("planner-stale-" + UUID.randomUUID());
        PlannerService.ServiceResult created = plannerService.create(request);
        String sessionId = String.valueOf(created.body().get("sessionId"));
        String token = String.valueOf(created.body().get("sessionAccessToken"));

        PlannerMutationRequest mutation = new PlannerMutationRequest();
        mutation.setTargetStopId("day1-hongyadong");
        mutation.setReason("看夜景");
        mutation.setExpectedVersion(1);
        mutation.setSessionAccessToken(token);
        assertEquals(200, plannerService.replan(sessionId, mutation).status());

        PlannerMutationRequest stale = new PlannerMutationRequest();
        stale.setTargetStopId("day1-jiefangbei");
        stale.setReason("少走路");
        stale.setExpectedVersion(1);
        stale.setSessionAccessToken(token);
        PlannerService.ServiceResult conflict = plannerService.replan(sessionId, stale);
        assertEquals(409, conflict.status());
        assertTrue(String.valueOf(conflict.body().get("message")).contains("版本"));
    }

    @Test
    void appliedPreferenceSnapshotIsHistoricalAndSurvivesSessionMutation() {
        String userId = "planner-preferences-" + UUID.randomUUID();
        String sessionId = null;
        String secondSessionId = null;
        UserContext.set(userId, "USER");
        try {
            PreferencesService.MutationResult firstPreference = preferencesService.merge(userId,
                    new UserPreferencesPatch(
                            List.of("夜景"), UserPreferences.WalkingTolerance.LOW, null,
                            UserPreferences.CompanionPreference.PARENTS,
                            UserPreferences.TransportPreference.PUBLIC_TRANSIT,
                            UserPreferences.DietPreference.UNSPECIFIED, "解放碑",
                            Map.of("legacyValue", "少走路"), 0L), 0L);

            PlanRequest firstRequest = new PlanRequest();
            firstRequest.setUsePreferences(true);
            firstRequest.setPrompt("");
            firstRequest.setIdempotencyKey("planner-preference-history-" + UUID.randomUUID());
            PlannerService.ServiceResult first = plannerService.create(firstRequest);
            assertEquals(200, first.status());
            sessionId = String.valueOf(first.body().get("sessionId"));
            Map<?, ?> firstSnapshot = (Map<?, ?>) first.body().get("appliedPreferences");
            assertEquals(firstPreference.preferences().revision(), firstSnapshot.get("preferenceRevision"));
            assertEquals("LOW", ((Map<?, ?>) firstSnapshot.get("appliedFields")).get("walkingTolerance"));
            assertEquals("PUBLIC_TRANSIT", ((Map<?, ?>) firstSnapshot.get("appliedFields")).get("transportPreference"));
            assertFalse(((Map<?, ?>) firstSnapshot.get("appliedFields")).containsKey("legacyMetadata"));

            PreferencesService.MutationResult secondPreference = preferencesService.merge(userId,
                    new UserPreferencesPatch(
                            List.of("美食"), UserPreferences.WalkingTolerance.NORMAL, null,
                            null, null, null, null, null,
                            firstPreference.preferences().revision()),
                    firstPreference.preferences().revision());

            PlannerService.ServiceResult historical = plannerService.get(sessionId, "");
            assertEquals(200, historical.status());
            Map<?, ?> historicalSnapshot = (Map<?, ?>) historical.body().get("appliedPreferences");
            assertEquals(firstSnapshot, historicalSnapshot);

            PlanRequest secondRequest = new PlanRequest();
            secondRequest.setUsePreferences(true);
            secondRequest.setPrompt("");
            secondRequest.setIdempotencyKey("planner-preference-history-" + UUID.randomUUID());
            PlannerService.ServiceResult second = plannerService.create(secondRequest);
            assertEquals(200, second.status());
            secondSessionId = String.valueOf(second.body().get("sessionId"));
            Map<?, ?> secondSnapshot = (Map<?, ?>) second.body().get("appliedPreferences");
            assertEquals(secondPreference.preferences().revision(), secondSnapshot.get("preferenceRevision"));
            assertEquals("NORMAL", ((Map<?, ?>) secondSnapshot.get("appliedFields")).get("walkingTolerance"));
            assertEquals(List.of("美食"), ((Map<?, ?>) secondSnapshot.get("appliedFields")).get("interests"));

            assertNotEquals(firstSnapshot, secondSnapshot);
        } finally {
            if (secondSessionId != null) deletePlannerSession(secondSessionId);
            if (sessionId != null) deletePlannerSession(sessionId);
            jdbcTemplate.update("DELETE FROM user_preference_audit WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM user_preferences WHERE user_id = ?", userId);
        }
    }

    @Test
    void opensFormalTripAsEditablePlannerSessionAndSavesBackToSameTrip() {
        String owner = "planner-reopen-" + UUID.randomUUID();
        String originalSessionId = null;
        String restoredSessionId = null;
        String tripId = null;
        UserContext.set(owner, "USER");
        try {
            PlanRequest request = new PlanRequest();
            request.setPrompt("带父母去重庆玩两天，喜欢人文夜景，尽量少走路");
            request.setIdempotencyKey("planner-reopen-create-" + UUID.randomUUID());
            PlannerService.ServiceResult created = plannerService.create(request);
            assertEquals(200, created.status());
            originalSessionId = String.valueOf(created.body().get("sessionId"));

            PlannerService.ServiceResult firstSave = plannerService.save(originalSessionId, "");
            assertEquals(201, firstSave.status());
            tripId = String.valueOf(firstSave.body().get("tripId"));
            assertEquals(1, firstSave.body().get("currentVersion"));

            PlannerService.ServiceResult reopened = plannerService.openFormalTrip(tripId);
            assertEquals(200, reopened.status());
            assertEquals(false, reopened.body().get("legacyMode"));
            assertEquals("V1_PROPOSAL", reopened.body().get("adjustmentCapability"));
            assertEquals(tripId, reopened.body().get("formalTripId"));
            assertEquals(1, reopened.body().get("formalTripVersion"));
            restoredSessionId = String.valueOf(reopened.body().get("sessionId"));
            assertNotEquals(originalSessionId, restoredSessionId);
            assertEquals(1, tripService.get(owner, tripId).currentVersion(), "重开不能修改正式 TripVersion");

            PlanConversationRequestDto previewRequest = PlanConversationRequestDto.builder()
                    .sessionId(restoredSessionId)
                    .baseRevision(1)
                    .message("第二天太赶了，少排一个")
                    .build();
            PlannerService.ServiceResult preview = plannerService.previewAdjustment(restoredSessionId, previewRequest);
            assertEquals(200, preview.status());
            assertNotNull(preview.body().get("proposalId"));
            assertEquals(1, tripService.get(owner, tripId).currentVersion(), "Preview 不能修改正式 TripVersion");
            assertEquals(1, ((Map<?, ?>) plannerService.get(restoredSessionId, "").body().get("trip")).get("version"),
                    "Preview 不能修改 PlannerSession revision");

            PlannerService.ServiceResult applied = plannerService.applyAdjustment(restoredSessionId,
                    ApplyAdjustmentRequestDto.builder()
                            .proposalId(String.valueOf(preview.body().get("proposalId")))
                            .optionId("option-1")
                            .baseRevision(1)
                            .build());
            assertEquals(200, applied.status());
            assertEquals(2, applied.body().get("currentVersion"));
            assertEquals(1, tripService.get(owner, tripId).currentVersion(), "Apply 只修改 PlannerSession revision");

            PlannerService.ServiceResult secondSave = plannerService.save(restoredSessionId, "");
            assertEquals(200, secondSave.status());
            assertEquals(tripId, secondSave.body().get("tripId"));
            assertEquals(2, secondSave.body().get("currentVersion"));
            assertEquals(2, tripService.get(owner, tripId).currentVersion());
        } finally {
            if (originalSessionId != null) deletePlannerSession(originalSessionId);
            if (restoredSessionId != null) deletePlannerSession(restoredSessionId);
            if (tripId != null) {
                jdbcTemplate.update("DELETE FROM trip_idempotency WHERE trip_id = ?", tripId);
                jdbcTemplate.update("DELETE FROM trip_version WHERE trip_id = ?", tripId);
                jdbcTemplate.update("DELETE FROM trip WHERE id = ?", tripId);
            }
        }
    }

    @Test
    void restoredPlannerSaveRejectsStaleFormalTripVersion() {
        String owner = "planner-restore-conflict-" + UUID.randomUUID();
        String sourceSessionId = null;
        String restoredSessionId = null;
        String tripId = null;
        UserContext.set(owner, "USER");
        try {
            PlanRequest request = new PlanRequest();
            request.setPrompt("重庆两天，喜欢夜景");
            request.setIdempotencyKey("planner-restore-conflict-create-" + UUID.randomUUID());
            PlannerService.ServiceResult created = plannerService.create(request);
            sourceSessionId = String.valueOf(created.body().get("sessionId"));
            PlannerService.ServiceResult saved = plannerService.save(sourceSessionId, "");
            tripId = String.valueOf(saved.body().get("tripId"));

            PlannerService.ServiceResult restored = plannerService.openFormalTrip(tripId);
            restoredSessionId = String.valueOf(restored.body().get("sessionId"));
            Map<String, Object> externallyChanged = new java.util.LinkedHashMap<>(tripService.get(owner, tripId).plan());
            externallyChanged.put("title", "其他设备已保存的新版本");
            TripService.OperationResult externalUpdate = tripService.update(owner, tripId, Map.of(
                    "plan", externallyChanged,
                    "expectedVersion", 1,
                    "idempotencyKey", "external-update-" + UUID.randomUUID()));
            assertEquals(2, externalUpdate.trip().currentVersion());

            PlannerService.ServiceResult staleSave = plannerService.save(restoredSessionId, "");
            assertEquals(409, staleSave.status());
            assertEquals(2, tripService.get(owner, tripId).currentVersion(), "冲突保存不得覆盖其他客户端版本");
        } finally {
            if (sourceSessionId != null) deletePlannerSession(sourceSessionId);
            if (restoredSessionId != null) deletePlannerSession(restoredSessionId);
            if (tripId != null) {
                jdbcTemplate.update("DELETE FROM trip_idempotency WHERE trip_id = ?", tripId);
                jdbcTemplate.update("DELETE FROM trip_version WHERE trip_id = ?", tripId);
                jdbcTemplate.update("DELETE FROM trip WHERE id = ?", tripId);
            }
        }
    }

    @Test
    void shadowPlanningAndMutationsNeverCreateDurablePlannerOrTripState() {
        Integer sessionsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM planner_session", Integer.class);
        Integer revisionsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM planner_plan_revision", Integer.class);
        Integer tripsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip", Integer.class);
        Integer tripVersionsBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_version", Integer.class);

        ShadowPlanRequest plan = new ShadowPlanRequest();
        plan.setPrompt("周六下午到重庆，周日晚上离开，带父母，希望少走路，喜欢夜景。");
        plan.setUsePreferences(true);
        PlannerService.ServiceResult generated = plannerService.shadow(plan);

        assertEquals(200, generated.status());
        assertEquals(true, generated.body().get("ok"));
        assertEquals(false, generated.body().get("persisted"));
        assertFalse(generated.body().containsKey("sessionId"));
        assertFalse(generated.body().containsKey("sessionAccessToken"));
        assertNotNull(generated.body().get("trip"));

        ShadowMutationRequest replan = new ShadowMutationRequest();
        @SuppressWarnings("unchecked")
        Map<String, Object> draft = (Map<String, Object>) generated.body().get("trip");
        replan.setTrip(draft);
        replan.setTargetStopId("day1-hongyadong");
        replan.setReason("少走路");
        PlannerService.ServiceResult replanned = plannerService.shadowReplan(replan);
        assertEquals(200, replanned.status());
        assertEquals(false, replanned.body().get("persisted"));
        assertNotNull(replanned.body().get("replacementVenueId"));
        assertNotEquals("", replanned.body().get("replacementVenueId"));
        assertNotEquals("cq-hongyadong", replanned.body().get("replacementVenueId"));

        ShadowMutationRequest add = new ShadowMutationRequest();
        add.setTrip(draft);
        add.setOperation("add");
        add.setAttractionId("cq-beicang");
        add.setDay(1);
        PlannerService.ServiceResult added = plannerService.shadowMutateStops(add);
        assertEquals(200, added.status());
        assertEquals(false, added.body().get("persisted"));
        assertEquals("add", added.body().get("operation"));

        assertEquals(sessionsBefore, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM planner_session", Integer.class));
        assertEquals(revisionsBefore, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM planner_plan_revision", Integer.class));
        assertEquals(tripsBefore, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip", Integer.class));
        assertEquals(tripVersionsBefore, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM trip_version", Integer.class));
    }

    private void deletePlannerSession(String sessionId) {
        jdbcTemplate.update("DELETE FROM planner_plan_revision WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM planner_session WHERE session_id = ?", sessionId);
    }
}
