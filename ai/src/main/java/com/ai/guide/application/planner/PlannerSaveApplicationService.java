package com.ai.guide.application.planner;

import com.ai.guide.domain.planner.gateway.PlannerSaveUseCasePort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.model.PlannerSaveActor;
import com.ai.guide.domain.planner.model.PlannerSaveResult;
import com.ai.guide.domain.trip.service.TripService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Coordinates the V1 planner-session to formal-trip save use case. */
@Service
public class PlannerSaveApplicationService implements PlannerSaveUseCasePort {
    private static final Logger log = LoggerFactory.getLogger(PlannerSaveApplicationService.class);

    private final PlannerSessionRepositoryPort sessionRepository;
    private final TripService tripService;

    public PlannerSaveApplicationService(PlannerSessionRepositoryPort sessionRepository, TripService tripService) {
        this.sessionRepository = sessionRepository;
        this.tripService = tripService;
    }

    @Override
    public PlannerSaveResult save(PlannerSaveActor actor, String sessionId, String accessToken) {
        if (actor == null || actor.anonymous()) return error(401, "请先登录后保存行程。", null, null, null);
        StoredSession session = findAuthorized(actor, sessionId, accessToken);
        if (session == null) return error(404, "规划会话不存在或无权访问。", null, null, null);

        Map<String, Object> plan = mutableMap(session.trip());
        // V1 deliberately saves the server-held session snapshot. No client trip is accepted here.
        plan.put("plannerSessionId", session.sessionId());
        plan.put("plannerVersion", session.currentVersion());
        plan.put("appliedPreferences", session.appliedPreferences().asMap());
        String saveKey = "planner-save-" + session.sessionId() + "-" + session.currentVersion();

        TripService.OperationResult saved;
        try {
            saved = tripService.savePlannerSnapshot(actor.userId(), plan, saveKey);
        } catch (DataAccessResourceFailureException exception) {
            // The existing save key is session+revision idempotency, so one retry is safe.
            log.warn("[Planner] 保存行程遇到瞬时数据库断连，正在使用新连接重试一次: {}",
                    exception.getMostSpecificCause().getMessage());
            saved = tripService.savePlannerSnapshot(actor.userId(), plan, saveKey);
        }

        if (saved.status() >= 400 || saved.trip() == null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("message", saved.message());
            body.put("appliedPreferences", session.appliedPreferences().asMap());
            if (saved.tripId() != null) body.put("tripId", saved.tripId());
            if (saved.expectedVersion() != null) body.put("expectedVersion", saved.expectedVersion());
            if (saved.currentVersion() != null) body.put("currentVersion", saved.currentVersion());
            return new PlannerSaveResult(saved.status(), body);
        }

        plan.put("formalTripId", saved.trip().id());
        plan.put("sourceTripId", saved.trip().id());
        plan.put("sourceTripVersion", saved.trip().currentVersion());
        boolean linked = sessionRepository.linkFormalTrip(session, plan);
        // A concurrent adjustment owns the newer business snapshot. Retry only identity metadata.
        for (int attempt = 0; !linked && attempt < 3; attempt++) {
            StoredSession latest = findAuthorized(actor, sessionId, accessToken);
            if (latest == null) break;
            Map<String, Object> latestPlan = mutableMap(latest.trip());
            String existingId = safe(latestPlan.get("formalTripId"));
            if (!existingId.isBlank() && !existingId.equals(saved.trip().id())) break;
            int baseline = 0;
            try { baseline = Integer.parseInt(safe(latestPlan.get("sourceTripVersion"))); }
            catch (NumberFormatException ignored) { /* Legacy snapshots may not carry a base version. */ }
            latestPlan.put("formalTripId", saved.trip().id());
            latestPlan.put("sourceTripId", saved.trip().id());
            latestPlan.put("sourceTripVersion", Math.max(baseline, saved.trip().currentVersion()));
            linked = sessionRepository.linkFormalTrip(latest, latestPlan);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("trip", saved.trip());
        body.put("tripId", saved.trip().id());
        body.put("currentVersion", saved.trip().currentVersion());
        body.put("appliedPreferences", session.appliedPreferences().asMap());
        body.put("draftLinked", linked);
        if (!linked) body.put("message", "正式行程已保存，草稿仍在更新；再次保存将继续更新同一正式行程。");
        return new PlannerSaveResult(saved.status(), body);
    }

    private StoredSession findAuthorized(PlannerSaveActor actor, String sessionId, String token) {
        if (sessionId == null || sessionId.isBlank()) return null;
        StoredSession owned = sessionRepository.find(sessionId, "USER", actor.userId(), token);
        return owned != null ? owned : sessionRepository.claimAnonymous(sessionId, token, actor.userId());
    }

    private PlannerSaveResult error(int status, String message, String tripId,
                                    Integer expectedVersion, Integer currentVersion) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        if (tripId != null) body.put("tripId", tripId);
        if (expectedVersion != null) body.put("expectedVersion", expectedVersion);
        if (currentVersion != null) body.put("currentVersion", currentVersion);
        return new PlannerSaveResult(status, body);
    }

    private Map<String, Object> mutableMap(Map<String, Object> source) {
        return source == null ? new LinkedHashMap<>() : new LinkedHashMap<>(source);
    }

    private String safe(Object value) { return value == null ? "" : String.valueOf(value); }
}
