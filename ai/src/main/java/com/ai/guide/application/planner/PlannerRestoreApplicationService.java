package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.gateway.PlannerRestoreUseCasePort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.MutationResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.model.PlannerRestoreActor;
import com.ai.guide.domain.planner.model.PlannerRestoreResult;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.RestoreDraftRequest;
import com.ai.guide.domain.planner.service.PlannerMetricsLogger;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.service.TripPlanLimits;
import com.ai.guide.domain.trip.service.TripService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Coordinates restoring a browser backup only into its currently authorized planner session. */
@Service
public class PlannerRestoreApplicationService implements PlannerRestoreUseCasePort {
    private final PlannerSessionRepositoryPort sessionRepository;
    private final TripService tripService;
    private final TripPlanLimits planLimits;
    private final PlannerMetricsLogger metricsLogger;
    private final boolean plannerV1Enabled;

    public PlannerRestoreApplicationService(PlannerSessionRepositoryPort sessionRepository,
                                            TripService tripService,
                                            TripPlanLimits planLimits,
                                            PlannerMetricsLogger metricsLogger,
                                            @Value("${planner.v1.enabled:true}") boolean plannerV1Enabled) {
        this.sessionRepository = sessionRepository;
        this.tripService = tripService;
        this.planLimits = planLimits;
        this.metricsLogger = metricsLogger;
        this.plannerV1Enabled = plannerV1Enabled;
    }

    @Override
    public PlannerRestoreResult restoreDraft(PlannerRestoreActor actor, String sessionId,
                                             RestoreDraftRequest request) {
        if (actor == null || actor.anonymous()) return error(401, "登录后才能恢复正式行程草稿。", null);
        StoredSession session = findAuthorized(actor, sessionId,
                request == null ? "" : safe(request.sessionAccessToken()));
        if (session == null) return error(404, "规划会话不存在或无权访问。", null);
        if (request == null || request.trip() == null || request.expectedVersion() == null
                || request.expectedVersion() != session.currentVersion()) {
            return conflict(session);
        }
        String formalId = safe(session.trip().get("sourceTripId"));
        if (formalId.isBlank()) return error(400, "此会话未关联正式行程。", session);
        Trip formal = tripService.get(actor.userId(), formalId);
        if (formal == null) return error(404, "正式行程不存在或无权访问。", session);
        Integer sourceVersion = number(request.trip().get("sourceTripVersion"));
        if (sourceVersion == null || sourceVersion != formal.currentVersion()) {
            return error(409, "正式行程已更新，请先查看最新版本再恢复备份。", session);
        }
        Map<String, Object> restored = mutableMap(request.trip());
        try {
            planLimits.validate(restored);
        } catch (IllegalArgumentException invalid) {
            return error(400, invalid.getMessage(), session);
        }
        if (!com.ai.guide.domain.planner.service.PlannerPins.retained(session.trip(), restored))
            return error(400, "草稿会移除或替换已固定站点，请先取消固定再恢复。", session);
        com.ai.guide.domain.planner.service.PlannerPins.preserve(session.trip(), restored);
        // Session and formal identity are authoritative; browser-provided identity/revision are ignored.
        restored.put("sourceTripId", formalId);
        restored.put("formalTripId", formalId);
        restored.put("sourceTripVersion", formal.currentVersion());
        restored.put("version", session.currentVersion() + 1);
        MutationResult mutation = sessionRepository.update(session, restored, session.currentVersion(),
                "RESTORE_DRAFT", "恢复本地草稿", "用户确认恢复离线备份", List.of());
        if ("CONFLICT".equals(mutation.status())) return conflict(mutation.session());
        Map<String, Object> body = planResponse(mutation.session());
        body.put("message", "草稿已恢复到当前规划会话。");
        return new PlannerRestoreResult(200, body);
    }

    private StoredSession findAuthorized(PlannerRestoreActor actor, String sessionId, String token) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (actor.anonymous()) {
            StoredSession capabilitySession = sessionRepository.findAnonymousByAccessToken(token);
            if (capabilitySession == null || !sessionId.equals(capabilitySession.sessionId())) return null;
            return sessionRepository.find(sessionId, "ANONYMOUS", capabilitySession.ownerId(), token);
        }
        StoredSession owned = sessionRepository.find(sessionId, "USER", actor.userId(), token);
        return owned != null ? owned : sessionRepository.claimAnonymous(sessionId, token, actor.userId());
    }

    private Map<String, Object> planResponse(StoredSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("sessionId", session.sessionId());
        response.put("trip", session.trip());
        response.put("appliedPreferences", session.appliedPreferences().asMap());
        response.put("syncRevision", session.syncRevision());
        Map<String, Object> trip = session.trip();
        String routeStatus = trip == null ? "ESTIMATED" : String.valueOf(trip.getOrDefault("routeDataStatus", "ESTIMATED"));
        boolean degraded = !plannerV1Enabled || trip == null || Boolean.TRUE.equals(trip.get("degraded"))
                || !"高德实时接口".equals(trip.get("sourceMode"));
        response.put("plannerVersion", plannerV1Enabled ? PlannerVersionMetadata.CURRENT_PLANNER_VERSION : "legacy-v0");
        response.put("policyVersion", plannerV1Enabled ? PlannerVersionMetadata.CURRENT_POLICY_VERSION : "legacy-2026.07");
        response.put("explanationSource", !plannerV1Enabled ? "LEGACY_STATIC" : trip != null && trip.containsKey("explanationSource")
                ? String.valueOf(trip.get("explanationSource")) : PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
        response.put("routeDataStatus", routeStatus);
        response.put("degraded", degraded);
        response.put("degradationReasons", !plannerV1Enabled ? List.of("LEGACY_PLANNER_ACTIVE") : degradationReasons(trip, degraded));
        return response;
    }

    private List<String> degradationReasons(Map<String, Object> trip, boolean degraded) {
        List<String> reasons = new ArrayList<>();
        if (trip != null && trip.get("degradationReasons") instanceof List<?> values) {
            for (Object value : values) if (value != null && !String.valueOf(value).isBlank()) reasons.add(String.valueOf(value));
        }
        if (degraded && reasons.isEmpty()) reasons.add("DEMO_OR_ESTIMATED_ROUTE_FALLBACK");
        return reasons;
    }

    private PlannerRestoreResult error(int status, String message, StoredSession session) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        if (session != null) {
            body.put("sessionId", session.sessionId());
            body.put("trip", session.trip());
            body.put("appliedPreferences", session.appliedPreferences().asMap());
        }
        return new PlannerRestoreResult(status, body);
    }

    private PlannerRestoreResult conflict(StoredSession session) {
        if (metricsLogger != null && session != null) {
            metricsLogger.logRevisionConflict(session.sessionId(), session.currentVersion(), session.currentVersion());
        }
        return error(409, "规划版本已变化，请刷新后重试。", session);
    }

    private Map<String, Object> mutableMap(Map<String, Object> source) {
        return new LinkedHashMap<>(source == null ? Map.of() : source);
    }

    private String safe(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private Integer number(Object value) {
        try { return value == null ? null : Integer.valueOf(String.valueOf(value)); }
        catch (NumberFormatException e) { return null; }
    }
}
