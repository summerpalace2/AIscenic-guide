package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.gateway.PlannerOpenUseCasePort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.CreateResult;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerOpenActor;
import com.ai.guide.domain.planner.model.PlannerOpenResult;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.service.TripService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Coordinates opening one owner-authorized formal Trip as a new V1 planner session. */
@Service
public class PlannerOpenApplicationService implements PlannerOpenUseCasePort {
    private final PlannerSessionRepositoryPort sessionRepository;
    private final TripService tripService;
    private final ObjectMapper objectMapper;
    private final boolean plannerV1Enabled;

    public PlannerOpenApplicationService(PlannerSessionRepositoryPort sessionRepository,
                                         TripService tripService,
                                         ObjectMapper objectMapper,
                                         @Value("${planner.v1.enabled:true}") boolean plannerV1Enabled) {
        this.sessionRepository = sessionRepository;
        this.tripService = tripService;
        this.objectMapper = objectMapper;
        this.plannerV1Enabled = plannerV1Enabled;
    }

    public PlannerOpenResult openFormalTrip(PlannerOpenActor actor, String tripId) {
        return openFormalTrip(actor, tripId, UUID.randomUUID().toString());
    }

    @Override
    public PlannerOpenResult openFormalTrip(PlannerOpenActor actor, String tripId, String operationKey) {
        if (actor == null || actor.anonymous() || safe(actor.userId()).isBlank())
            return error(401, "请先登录后打开正式行程。");
        String formalTripId = safe(tripId);
        if (formalTripId.isBlank()) return error(400, "缺少正式行程 ID。");
        String requestKey = safe(operationKey);
        if (requestKey.isBlank()) requestKey = UUID.randomUUID().toString();
        if (requestKey.length() > 128) return error(400, "Open 操作键长度不能超过 128 个字符。");

        Trip formal = tripService.get(actor.userId(), formalTripId);
        if (formal == null) return error(404, "正式行程不存在或无权访问。");

        Map<String, Object> restoredPlan = copyTripSnapshot(formal.plan());
        if (restoredPlan.isEmpty()) return error(400, "正式行程缺少可恢复的规划快照。");
        restoredPlan.put("title", formal.title());
        restoredPlan.put("formalTripId", formal.id());
        restoredPlan.put("sourceTripId", formal.id());
        restoredPlan.put("sourceTripVersion", formal.currentVersion());
        restoredPlan.put("sourcePlannerRevision", restoredPlan.getOrDefault("version", 1));
        restoredPlan.put("plannerSessionRestoreSource", "RESTORED_FROM_TRIP");
        restoredPlan.put("version", 1);

        TravelConstraints constraints = restoreConstraints(restoredPlan);
        AppliedPreferencesSnapshot appliedPreferences = restoreAppliedPreferences(restoredPlan);
        if (appliedPreferences.isEmpty()) {
            restoredPlan.put("plannerSessionRestoreDegradation", "APPLIED_PREFERENCES_UNAVAILABLE");
        }
        String prompt = safe(restoredPlan.get("prompt"));
        if (prompt.isBlank()) prompt = safe(restoredPlan.get("input"));
        String restoreKey = openIdempotencyKey(formal.id(), requestKey);
        String restoreFingerprint = fingerprint(prompt, constraints.asMap(), false, appliedPreferences,
                "RESTORED_FROM_TRIP:" + formal.id() + ":" + formal.currentVersion());
        CreateResult created = sessionRepository.create("USER", actor.userId(), prompt,
                constraints.asMap(), appliedPreferences, restoredPlan, restoreKey, restoreFingerprint);
        if ("CONFLICT".equals(created.status())) {
            return error(409, "该 Open 操作键已用于不同版本的正式行程，请发起新的打开操作。");
        }
        Map<String, Object> response = planResponse(created.session());
        response.put("formalTripId", formal.id());
        response.put("formalTripVersion", formal.currentVersion());
        response.put("restoredFromTrip", true);
        response.put("adjustmentCapability", "V1_PROPOSAL");
        response.put("legacyMode", false);
        return new PlannerOpenResult(200, response);
    }

    @Override
    public PlannerOpenResult acknowledgeOpenSession(PlannerOpenActor actor, String tripId, String sessionId) {
        if (actor == null || actor.anonymous() || safe(actor.userId()).isBlank())
            return error(401, "请先登录后确认正式行程打开状态。");
        String formalTripId = safe(tripId);
        String plannerSessionId = safe(sessionId);
        if (formalTripId.isBlank() || plannerSessionId.isBlank()) return error(400, "缺少行程或规划会话 ID。");
        var session = sessionRepository.find(plannerSessionId, "USER", actor.userId(), "");
        if (session == null) return error(404, "规划会话不存在或无权访问。");
        Map<String, Object> trip = session.trip();
        boolean sameFormalTrip = formalTripId.equals(safe(trip.get("formalTripId")))
                && formalTripId.equals(safe(trip.get("sourceTripId")));
        boolean openedFromFormalTrip = "RESTORED_FROM_TRIP".equals(safe(trip.get("plannerSessionRestoreSource")));
        if (!sameFormalTrip || !openedFromFormalTrip) return error(409, "该规划会话与正式行程不匹配。");
        if (!sessionRepository.activatePendingFormalOpen(plannerSessionId, actor.userId())) {
            return error(409, "该规划会话无法确认，请使用相同操作键重试打开。");
        }
        return new PlannerOpenResult(200, Map.of("ok", true, "acknowledged", true));
    }

    @Override
    public PlannerOpenResult discardUncommittedOpen(PlannerOpenActor actor, String tripId, String sessionId) {
        if (actor == null || actor.anonymous() || safe(actor.userId()).isBlank())
            return error(401, "请先登录后清理未完成的行程打开操作。");
        String formalTripId = safe(tripId);
        String plannerSessionId = safe(sessionId);
        if (formalTripId.isBlank() || plannerSessionId.isBlank()) return error(400, "缺少行程或规划会话 ID。");

        var session = sessionRepository.find(plannerSessionId, "USER", actor.userId(), "");
        // A retry after successful compensation is intentionally idempotent.
        if (session == null) return new PlannerOpenResult(200, Map.of("ok", true, "discarded", true, "alreadyAbsent", true));
        Map<String, Object> trip = session.trip();
        boolean sameFormalTrip = formalTripId.equals(safe(trip.get("formalTripId")))
                && formalTripId.equals(safe(trip.get("sourceTripId")));
        boolean openedFromFormalTrip = "RESTORED_FROM_TRIP".equals(safe(trip.get("plannerSessionRestoreSource")));
        if (!sameFormalTrip || !openedFromFormalTrip || !"OPEN_PENDING".equals(session.status())
                || session.currentVersion() != 1 || session.syncRevision() != 1) {
            return error(409, "该规划会话已变化或不属于此正式行程，不能作为未完成打开操作清理。");
        }
        if (!sessionRepository.deleteUnchangedUserSession(plannerSessionId, actor.userId(), 1)) {
            return error(409, "规划会话已变化，请保留该会话并重新核对。");
        }
        return new PlannerOpenResult(200, Map.of("ok", true, "discarded", true, "alreadyAbsent", false));
    }

    private Map<String, Object> planResponse(PlannerSessionRepositoryPort.StoredSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("sessionId", session.sessionId());
        response.put("trip", session.trip());
        response.put("appliedPreferences", session.appliedPreferences().asMap());
        response.put("syncRevision", session.syncRevision());
        Map<String, Object> trip = session.trip();
        response.putAll(PlannerVersionMetadata.fromTrip(trip, plannerV1Enabled).asMap());
        return response;
    }

    private TravelConstraints restoreConstraints(Map<String, Object> plan) {
        Object raw = plan.get("constraints");
        if (raw instanceof Map<?, ?> values) {
            try { return objectMapper.convertValue(values, TravelConstraints.class); }
            catch (IllegalArgumentException ignored) { plan.put("plannerSessionRestoreDegradation", "CONSTRAINTS_UNAVAILABLE"); }
        }
        return new TravelConstraints();
    }

    private AppliedPreferencesSnapshot restoreAppliedPreferences(Map<String, Object> plan) {
        Object raw = plan.get("appliedPreferences");
        if (!(raw instanceof Map<?, ?> values)) return AppliedPreferencesSnapshot.empty();
        try { return AppliedPreferencesSnapshot.fromJson(objectMapper.writeValueAsString(values), objectMapper); }
        catch (Exception ignored) { return AppliedPreferencesSnapshot.empty(); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copyTripSnapshot(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return new LinkedHashMap<>();
        try { return objectMapper.readValue(objectMapper.writeValueAsBytes(value), LinkedHashMap.class); }
        catch (Exception ignored) { return new LinkedHashMap<>(value); }
    }

    String fingerprint(String prompt, Map<String, Object> constraints, boolean usePreferences,
                               AppliedPreferencesSnapshot preferences, String decision) {
        try {
            Map<String, Object> semantic = new LinkedHashMap<>();
            semantic.put("prompt", prompt);
            semantic.put("constraints", constraints);
            semantic.put("usePreferences", usePreferences);
            semantic.put("appliedPreferencesFingerprint", preferences == null ? "" : preferences.fingerprint());
            semantic.put("preferenceDecision", decision == null ? "" : decision);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    objectMapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                            .writeValueAsString(semantic).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成规划请求指纹", e);
        }
    }

    private String openIdempotencyKey(String tripId, String operationKey) {
        try {
            String value = tripId + "\n" + operationKey;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("trip-open-");
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成正式行程 Open 操作键", e);
        }
    }

    private PlannerOpenResult error(int status, String message) {
        return new PlannerOpenResult(status, Map.of("ok", false, "message", message));
    }

    private String safe(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
}
