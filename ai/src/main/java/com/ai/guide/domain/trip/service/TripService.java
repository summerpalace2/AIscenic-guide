package com.ai.guide.domain.trip.service;

import com.ai.guide.domain.planner.model.PlannerSession;
import com.ai.guide.domain.trip.gateway.TripRepositoryPort;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 正式行程聚合根与版本演进服务
 *
 * 所属领域：domain.trip（正式行程与版本域）
 * 架构职责：管理用户已确认保存的正式行程生命周期，每次内容变更都会生成不可变的完整快照 (TripVersion) 并原子递增版本指针，同时支持客户端幂等提交。
 *
 * 核心方法与职责：
 * 1. list：查询指定用户拥有的全部正式行程列表
 * 2. get：查询单条正式行程的最新状态与完整排程数据
 * 3. create：创建新行程，支持请求体中的 idempotencyKey 幂等校验与初始化版本 1 快照
 *    - 参数：ownerId（行程拥有者 ID），request（包含行程标题与计划数据的 Map）
 *    - 返回值：包含状态码与行程实体的 OperationResult
 * 4. update：修改行程内容，版本号自动自增并在 trip_version 插入新快照
 * 5. delete：软删除指定行程
 * 6. versions：按版本号倒序查询指定行程的历史快照轨迹
 */
@Service
public class TripService {

    private final TripRepositoryPort repository;
    private final TripReplanEngine replanEngine;
    private final ObjectMapper objectMapper;
    private TripPlanLimits planLimits;

    public TripService(TripRepositoryPort repository, TripReplanEngine replanEngine,
                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.replanEngine = replanEngine;
        this.objectMapper = objectMapper;
        this.planLimits = new TripPlanLimits(objectMapper);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setPlanLimits(TripPlanLimits planLimits) { this.planLimits = planLimits; }

    public List<Trip> list(String ownerId) {
        return repository.list(owner(ownerId));
    }

    public Trip get(String ownerId, String tripId) {
        return repository.find(owner(ownerId), tripId);
    }

    @Transactional
    public OperationResult create(String ownerId, Map<String, Object> request) {
        String effectiveOwner = owner(ownerId);
        WriteInput input;
        try { input = parseWrite(request); } catch (IllegalArgumentException invalid) { return OperationResult.badRequest(invalid.getMessage()); }
        String fingerprint = fingerprint("CREATE", input.title(), input.plan(), input.idempotencyKey());
        OperationResult replay = replay(effectiveOwner, "CREATE", input.idempotencyKey(), fingerprint);
        if (replay != null) return replay;

        String tripId = "trip-" + UUID.randomUUID();
        Map<String, Object> snapshot = withFormalId(input.plan(), tripId);
        String title = title(input.title(), snapshot);
        snapshot.put("title", title);
        try { planLimits.validate(snapshot); } catch (IllegalArgumentException invalid) {
            return OperationResult.badRequest(invalid.getMessage());
        }
        long now = System.currentTimeMillis();
        try {
            OperationResult reserved = reserveIdempotency(effectiveOwner, "CREATE", input.idempotencyKey(), fingerprint, tripId, now);
            if (reserved != null) return reserved;
            repository.insertTripAndVersion(tripId, effectiveOwner, title, "ACTIVE", snapshot,
                    now, effectiveOwner, "trip-version-" + UUID.randomUUID());
        } catch (DuplicateKeyException duplicate) {
            OperationResult winner = replay(effectiveOwner, "CREATE", input.idempotencyKey(), fingerprint);
            if (winner != null) return winner;
            throw duplicate;
        }
        Trip created = repository.find(effectiveOwner, tripId);
        if (created == null) throw new IllegalStateException("Trip 创建后无法读取: " + tripId);
        completeIdempotency(effectiveOwner, "CREATE", input.idempotencyKey(), created);
        return OperationResult.success(201, "行程已创建", "CREATED", created);
    }

    @Transactional
    public OperationResult update(String ownerId, String tripId, Map<String, Object> request) {
        String effectiveOwner = owner(ownerId);
        Map<String, Object> body = copyMap(request);
        if (requiredExpectedVersion(body) == null) {
            return OperationResult.badRequest("expectedVersion 为必填整数");
        }
        WriteInput input;
        try { input = parseWrite(body); } catch (IllegalArgumentException invalid) {
            Trip existing = repository.find(effectiveOwner, tripId);
            if (existing == null) return OperationResult.notFound("行程不存在");
            int expected = requiredExpectedVersion(body);
            if (expected != existing.currentVersion()) return conflict(tripId, expected, existing);
            return OperationResult.badRequest(invalid.getMessage());
        }
        String fingerprint = fingerprint("UPDATE", tripId, input.title(), input.plan(),
                input.expectedVersion(), input.idempotencyKey());
        OperationResult replay = replay(effectiveOwner, "UPDATE", input.idempotencyKey(), fingerprint);
        if (replay != null) return replay;

        Trip current = repository.find(effectiveOwner, tripId);
        if (current == null) return OperationResult.notFound("行程不存在");
        int expected = input.expectedVersion();
        if (expected != current.currentVersion()) return conflict(tripId, expected, current);

        Map<String, Object> snapshot = withFormalId(input.plan(), tripId);
        String title = title(input.title().isBlank() ? current.title() : input.title(), snapshot);
        snapshot.put("title", title);
        try { planLimits.validate(snapshot); } catch (IllegalArgumentException invalid) {
            return OperationResult.badRequest(invalid.getMessage());
        }
        if (sameSnapshot(current.plan(), snapshot) && current.title().equals(title)) {
            OperationResult reserved = reserveIdempotency(effectiveOwner, "UPDATE", input.idempotencyKey(), fingerprint,
                    tripId, System.currentTimeMillis());
            if (reserved != null) return reserved;
            completeIdempotency(effectiveOwner, "UPDATE", input.idempotencyKey(), current);
            return OperationResult.success(200, "行程未发生变化", "NOOP", current);
        }

        int nextVersion = current.currentVersion() + 1;
        long now = System.currentTimeMillis();
        OperationResult reserved = reserveIdempotency(effectiveOwner, "UPDATE", input.idempotencyKey(), fingerprint, tripId, now);
        if (reserved != null) return reserved;

        int updated = 0;
        int maxCasRetries = 3;
        for (int attempt = 0; attempt < maxCasRetries; attempt++) {
            updated = repository.advanceCurrentVersion(effectiveOwner, tripId, expected,
                    nextVersion, title, now);
            if (updated > 0) {
                break;
            }
            // CAS 失败时轻量重试循环（最多 3 次，10~30ms Jitter）
            try {
                Thread.sleep(java.util.concurrent.ThreadLocalRandom.current().nextInt(10, 31));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            Trip latest = repository.find(effectiveOwner, tripId);
            if (latest == null) {
                break;
            }
            if (sameSnapshot(latest.plan(), snapshot) && latest.title().equals(title)) {
                completeIdempotency(effectiveOwner, "UPDATE", input.idempotencyKey(), latest);
                return OperationResult.success(200, "行程未发生变化", "NOOP", latest);
            }
            Map<String, Object> rebased = tryRebase(current.plan(), latest.plan(), snapshot);
            if (rebased != null) {
                snapshot = withFormalId(rebased, tripId);
                title = title(text(snapshot.get("title")), snapshot);
                snapshot.put("title", title);
                try { planLimits.validate(snapshot); } catch (IllegalArgumentException invalid) {
                    repository.deleteIdempotency(effectiveOwner, "UPDATE", input.idempotencyKey());
                    return OperationResult.badRequest(invalid.getMessage());
                }
                expected = latest.currentVersion();
                nextVersion = expected + 1;
                now = System.currentTimeMillis();
                current = latest;
            } else {
                break;
            }
        }

        if (updated == 0) {
            repository.deleteIdempotency(effectiveOwner, "UPDATE", input.idempotencyKey());
            Trip latest = repository.find(effectiveOwner, tripId);
            return conflict(tripId, expected, latest);
        }
        repository.insertVersion("trip-version-" + UUID.randomUUID(), tripId, nextVersion,
                snapshot, input.changeReason().isBlank() ? "更新行程" : input.changeReason(),
                now, effectiveOwner);
        Trip result = repository.find(effectiveOwner, tripId);
        if (result == null) throw new IllegalStateException("Trip 更新后无法读取: " + tripId);
        completeIdempotency(effectiveOwner, "UPDATE", input.idempotencyKey(), result);
        return OperationResult.success(200, "行程已更新", "UPDATED", result);
    }

    @Transactional
    public OperationResult replan(String ownerId, String tripId, Map<String, Object> request) {
        String effectiveOwner = owner(ownerId);
        Map<String, Object> body = copyMap(request);
        Integer expectedVersion = requiredExpectedVersion(body);
        if (expectedVersion == null) {
            return OperationResult.badRequest("expectedVersion 为必填整数");
        }
        String idempotencyKey = text(body.get("idempotencyKey"));
        String fingerprint = fingerprint("REPLAN", tripId, body);
        OperationResult replay = replay(effectiveOwner, "REPLAN", idempotencyKey, fingerprint);
        if (replay != null) return replay;

        Trip current = repository.find(effectiveOwner, tripId);
        if (current == null) return OperationResult.notFound("行程不存在");
        int expected = expectedVersion;
        if (expected != current.currentVersion()) return conflict(tripId, expected, current);

        TripReplanEngine.ReplanResult replanned;
        try {
            replanned = replanEngine.replan(current.plan(), body, current.currentVersion() + 1);
        } catch (TripReplanEngine.BadRequestException badRequest) {
            return OperationResult.badRequest(badRequest.getMessage());
        }
        Map<String, Object> snapshot = withFormalId(replanned.plan(), tripId);
        String title = title(current.title(), snapshot);
        snapshot.put("title", title);
        try { planLimits.validate(snapshot); } catch (IllegalArgumentException invalid) {
            return OperationResult.badRequest(invalid.getMessage());
        }
        if (sameSnapshot(current.plan(), snapshot)) {
            OperationResult reserved = reserveIdempotency(effectiveOwner, "REPLAN", idempotencyKey, fingerprint,
                    tripId, System.currentTimeMillis());
            if (reserved != null) return reserved;
            completeIdempotency(effectiveOwner, "REPLAN", idempotencyKey, current);
            return OperationResult.success(200, "行程未发生变化", "NOOP", current);
        }

        int nextVersion = current.currentVersion() + 1;
        long now = System.currentTimeMillis();
        OperationResult reserved = reserveIdempotency(effectiveOwner, "REPLAN", idempotencyKey, fingerprint, tripId, now);
        if (reserved != null) return reserved;
        int updated = repository.advanceCurrentVersion(effectiveOwner, tripId, expected,
                nextVersion, title, now);
        if (updated == 0) {
            repository.deleteIdempotency(effectiveOwner, "REPLAN", idempotencyKey);
            Trip latest = repository.find(effectiveOwner, tripId);
            return conflict(tripId, expected, latest);
        }
        repository.insertVersion("trip-version-" + UUID.randomUUID(), tripId, nextVersion,
                snapshot, replanned.changeReason(), now, effectiveOwner);
        Trip result = repository.find(effectiveOwner, tripId);
        if (result == null) throw new IllegalStateException("Trip 重规划后无法读取: " + tripId);
        completeIdempotency(effectiveOwner, "REPLAN", idempotencyKey, result);
        return OperationResult.success(200, "局部重规划已保存", "UPDATED", result,
                replanned.replacementVenueId(), replanned.changedSegments());
    }

    @Transactional
    public OperationResult delete(String ownerId, String tripId, Map<String, Object> request) {
        Integer expectedVersion = requiredExpectedVersion(copyMap(request));
        if (expectedVersion == null) {
            return OperationResult.badRequest("expectedVersion 为必填整数");
        }

        String effectiveOwner = owner(ownerId);
        Trip current = repository.find(effectiveOwner, tripId);
        if (current == null) return OperationResult.notFound("行程不存在");
        if (expectedVersion != current.currentVersion()) {
            return conflict(tripId, expectedVersion, current);
        }

        int deleted = repository.deleteIfCurrentVersion(effectiveOwner, tripId, expectedVersion);
        if (deleted == 0) {
            Trip latest = repository.find(effectiveOwner, tripId);
            return latest == null
                    ? OperationResult.notFound("行程不存在")
                    : conflict(tripId, expectedVersion, latest);
        }
        return OperationResult.deleted(tripId, expectedVersion);
    }

    public List<TripVersion> versions(String ownerId, String tripId) {
        String effectiveOwner = owner(ownerId);
        if (repository.find(effectiveOwner, tripId) == null) return null;
        return repository.listVersions(effectiveOwner, tripId);
    }

    public TripVersion version(String ownerId, String tripId, int versionNumber) {
        return repository.findVersion(owner(ownerId), tripId, versionNumber);
    }

    /**
     * Trusted bridge used by PlannerSession.save. It never treats the planner's
     * draft id as the formal Trip id and returns the server-generated id in the
     * snapshot so the session can retain it for subsequent saves.
     */
    @Transactional
    public OperationResult savePlannerSnapshot(String ownerId, Map<String, Object> plan,
                                               String idempotencyKey) {
        String effectiveOwner = owner(ownerId);
        Map<String, Object> snapshot = copyMap(plan);
        try { planLimits.validate(snapshot); } catch (IllegalArgumentException invalid) {
            return OperationResult.badRequest(invalid.getMessage());
        }
        String key = text(idempotencyKey);
        String requestFingerprint = fingerprint("PLANNER_SAVE", plannerBusinessSnapshot(snapshot));
        OperationResult replay = replay(effectiveOwner, "PLANNER_SAVE", key, requestFingerprint);
        if (replay != null) {
            String requestedTripId = text(snapshot.get("formalTripId"));
            if (replay.trip() != null && !requestedTripId.isBlank()
                    && !requestedTripId.equals(replay.trip().id())) {
                return OperationResult.idempotencyConflict(replay.trip().id(), replay.trip().currentVersion());
            }
            return replay;
        }
        // This identity survives a process failure between committing the formal Trip
        // and linking its draft. Reserve it in the same transaction as the Trip write.
        String plannerSessionId = text(snapshot.get("plannerSessionId"));
        if (!plannerSessionId.isBlank()) {
            repository.reservePlannerLink(effectiveOwner, plannerSessionId, System.currentTimeMillis());
            TripRepositoryPort.IdempotencyRecord link = repository.findIdempotency(
                    effectiveOwner, "PLANNER_LINK", plannerSessionId);
            if (link != null && !text(link.tripId()).isBlank()) {
                String requestedTripId = text(snapshot.get("formalTripId"));
                if (!requestedTripId.isBlank() && !requestedTripId.equals(link.tripId())) {
                    return OperationResult.idempotencyConflict(link.tripId(), link.versionNumber());
                }
                snapshot.put("formalTripId", link.tripId());
                if (sourceTripVersion(snapshot.get("sourceTripVersion")) == null) {
                    snapshot.put("sourceTripVersion", link.versionNumber());
                }
            }
        }
        OperationResult reserved = reserveIdempotency(effectiveOwner, "PLANNER_SAVE", key,
                requestFingerprint, text(snapshot.get("formalTripId")), System.currentTimeMillis());
        if (reserved != null) return reserved;
        // The outer bridge owns the save key across CREATE, ID linking and UPDATE.
        // Internal mutations do not reuse it in different operation namespaces.
        OperationResult result = savePlannerSnapshotInternal(effectiveOwner, snapshot);
        if (result.status() >= 400 || result.trip() == null) {
            repository.deleteIdempotency(effectiveOwner, "PLANNER_SAVE", key);
            if (!plannerSessionId.isBlank()) {
                var link = repository.findIdempotency(effectiveOwner, "PLANNER_LINK", plannerSessionId);
                if (link != null && link.versionNumber() == 0) {
                    repository.deleteIdempotency(effectiveOwner, "PLANNER_LINK", plannerSessionId);
                }
            }
        } else {
            completeIdempotency(effectiveOwner, "PLANNER_SAVE", key, result.trip());
            if (!plannerSessionId.isBlank()) {
                var link = repository.findIdempotency(effectiveOwner, "PLANNER_LINK", plannerSessionId);
                // Keep the first saved baseline for a draft that never completed its
                // link, so later edits to the formal Trip still participate in CAS.
                if (link == null || link.versionNumber() == 0 || !result.trip().id().equals(link.tripId())) {
                    completeIdempotency(effectiveOwner, "PLANNER_LINK", plannerSessionId, result.trip());
                }
            }
        }
        return result;
    }

    private OperationResult savePlannerSnapshotInternal(String ownerId, Map<String, Object> plan) {
        Map<String, Object> snapshot = copyMap(plan);
        String formalTripId = text(snapshot.get("formalTripId"));
        String title = text(snapshot.get("title"));
        String key = "";
        if (formalTripId.isBlank()) {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("plan", snapshot);
            request.put("title", title);
            request.put("idempotencyKey", key);
            return create(ownerId, request);
        }

        Trip current = get(ownerId, formalTripId);
        if (current == null) {
            snapshot.remove("formalTripId");
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("plan", snapshot);
            request.put("title", title);
            request.put("idempotencyKey", key);
            return create(ownerId, request);
        }
        String effectiveTitle = title(title.isBlank() ? current.title() : title, snapshot);
        Map<String, Object> currentBusiness = plannerBusinessSnapshot(current.plan());
        currentBusiness.put("title", current.title());
        Map<String, Object> incomingBusiness = plannerBusinessSnapshot(snapshot);
        incomingBusiness.put("title", effectiveTitle);
        if (sameSnapshot(currentBusiness, incomingBusiness)) {
            return OperationResult.success(200, "行程未发生变化", "NOOP", current);
        }
        Integer sourceTripVersion = sourceTripVersion(snapshot.get("sourceTripVersion"));
        int expectedVersion = sourceTripVersion == null ? current.currentVersion() : sourceTripVersion;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("plan", snapshot);
        request.put("title", title.isBlank() ? current.title() : title);
        request.put("expectedVersion", expectedVersion);
        request.put("idempotencyKey", key);
        request.put("changeReason", "PlannerSession 保存");
        OperationResult res = update(ownerId, formalTripId, request);
        if (res.status() == 409 && (sourceTripVersion == null || sourceTripVersion != current.currentVersion())) {
            Trip latest = get(ownerId, formalTripId);
            if (latest != null && latest.currentVersion() != expectedVersion) {
                TripVersion baseVersion = (sourceTripVersion != null)
                        ? version(ownerId, formalTripId, sourceTripVersion) : null;
                Map<String, Object> basePlan = baseVersion != null ? baseVersion.snapshot() : current.plan();
                Map<String, Object> rebased = tryRebase(basePlan, latest.plan(), snapshot);
                if (rebased != null) {
                    Map<String, Object> retrySnapshot = withFormalId(rebased, formalTripId);
                    retrySnapshot.put("sourceTripVersion", latest.currentVersion());
                    Map<String, Object> retryRequest = new LinkedHashMap<>();
                    retryRequest.put("plan", retrySnapshot);
                    String baseTitle = basePlan.get("title") != null ? text(basePlan.get("title")) : "";
                    boolean titleModifiedByIncoming = !title.isBlank() && !title.equals(baseTitle);
                    String mergedTitle = titleModifiedByIncoming ? title : latest.title();
                    retryRequest.put("title", mergedTitle);
                    retryRequest.put("expectedVersion", latest.currentVersion());
                    retryRequest.put("idempotencyKey", key);
                    retryRequest.put("changeReason", "PlannerSession 变基合并保存");
                    return update(ownerId, formalTripId, retryRequest);
                }
            }
        }
        return res;
    }

    private static final Set<String> PLANNER_SAVE_METADATA_KEYS = Set.of(
            "id", "formalTripId", "sourceTripId", "sourceTripVersion", "plannerSessionId",
            "plannerVersion", "sourcePlannerRevision", "plannerSessionRestoreSource",
            "plannerSessionRestoreDegradation", "version", "status", "createdAt", "updatedAt"
    );

    private Map<String, Object> plannerBusinessSnapshot(Map<String, Object> plan) {
        Map<String, Object> result = copyMap(plan);
        PLANNER_SAVE_METADATA_KEYS.forEach(result::remove);
        result.put("title", title(text(result.get("title")), result));
        return result;
    }

    private static final Set<String> INTERNAL_METADATA_KEYS = PLANNER_SAVE_METADATA_KEYS;

    private Map<String, Object> tryRebase(Map<String, Object> base, Map<String, Object> latest, Map<String, Object> incoming) {
        if (base == null) base = Map.of();
        if (latest == null || incoming == null) return null;

        Map<String, Object> merged = new LinkedHashMap<>(latest);
        Set<String> allKeys = new HashSet<>();
        allKeys.addAll(base.keySet());
        allKeys.addAll(latest.keySet());
        allKeys.addAll(incoming.keySet());

        boolean incomingHasChanges = false;
        for (String key : allKeys) {
            if (INTERNAL_METADATA_KEYS.contains(key)) {
                continue;
            }

            Object baseVal = base.get(key);
            Object latestVal = latest.get(key);
            Object incVal = incoming.get(key);

            // Persisted JSON uses Integer while restored snapshots can use Long for the
            // same revision. Compare JSON meaning, not implementation-specific number classes.
            boolean latestChanged = !canonicalJson(baseVal).equals(canonicalJson(latestVal));
            boolean incChanged = !canonicalJson(baseVal).equals(canonicalJson(incVal));

            if (incChanged) {
                incomingHasChanges = true;
            }

            if (latestChanged && incChanged) {
                if (!canonicalJson(latestVal).equals(canonicalJson(incVal))) {
                    return null;
                }
                merged.put(key, incVal);
            } else if (incChanged) {
                merged.put(key, incVal);
            } else {
                if (latestVal != null) {
                    merged.put(key, latestVal);
                } else {
                    merged.remove(key);
                }
            }
        }
        if (!incomingHasChanges) {
            return null;
        }
        return merged;
    }

    private Integer sourceTripVersion(Object value) {
        if (value == null) return null;
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private OperationResult replay(String ownerId, String operation, String key, String fingerprint) {
        if (key == null || key.isBlank()) return null;
        TripRepositoryPort.IdempotencyRecord record = repository.findIdempotency(ownerId, operation, key);
        if (record == null) return null;
        if (!fingerprint.equals(record.requestFingerprint())) {
            return OperationResult.idempotencyConflict(record.tripId(), record.versionNumber());
        }
        Trip replay = repository.deserializeIdempotentTrip(record);
        if (replay == null) replay = repository.find(ownerId, record.tripId());
        return replay == null
                ? OperationResult.notFound("幂等结果关联的行程已不存在")
                : OperationResult.success(200, "已返回幂等结果", "EXISTING", replay);
    }

    private OperationResult reserveIdempotency(String ownerId, String operation, String key,
                                               String fingerprint, String tripId, long now) {
        if (key == null || key.isBlank()) return null;
        try {
            repository.reserveIdempotency(ownerId, operation, key, fingerprint, tripId, now);
            return null;
        } catch (DuplicateKeyException duplicate) {
            OperationResult winner = replay(ownerId, operation, key, fingerprint);
            if (winner != null) return winner;
            throw duplicate;
        }
    }

    private void completeIdempotency(String ownerId, String operation, String key, Trip trip) {
        if (key == null || key.isBlank()) return;
        repository.completeIdempotency(ownerId, operation, key, trip);
    }

    private OperationResult conflict(String tripId, int expected, Trip current) {
        return OperationResult.conflict(tripId, expected, current == null ? null : current.currentVersion());
    }

    private WriteInput parseWrite(Map<String, Object> request) {
        Map<String, Object> body = copyMap(request);
        Map<String, Object> plan;
        Object nested = body.get("plan");
        if (nested instanceof Map<?, ?>) {
            plan = copyMap(nested);
        } else {
            plan = new LinkedHashMap<>(body);
            plan.remove("expectedVersion");
            plan.remove("idempotencyKey");
            plan.remove("changeReason");
        }
        if (!(plan.get("days") instanceof List<?> days)) {
            throw new IllegalArgumentException("正式行程必须包含完整的 days 数组；修改标题也需提交完整计划");
        }
        for (Object day : days) {
            if (!(day instanceof Map<?, ?> map) || !(map.get("stops") instanceof List<?>)) {
                throw new IllegalArgumentException("行程每日安排必须包含 stops 数组");
            }
        }
        planLimits.validate(plan);
        String title = text(body.get("title"));
        if (title.isBlank()) title = text(plan.get("title"));
        return new WriteInput(plan, title, integer(body.get("expectedVersion")),
                text(body.get("idempotencyKey")), text(body.get("changeReason")));
    }

    private Map<String, Object> withFormalId(Map<String, Object> plan, String tripId) {
        Map<String, Object> copy = copyMap(plan);
        copy.put("formalTripId", tripId);
        return copy;
    }

    private String title(String candidate, Map<String, Object> plan) {
        String value = candidate == null ? "" : candidate.trim();
        if (value.isBlank()) value = text(plan.get("title"));
        return value.isBlank() ? "未命名行程" : value;
    }

    private boolean sameSnapshot(Map<String, Object> left, Map<String, Object> right) {
        return canonicalJson(left).equals(canonicalJson(right));
    }

    private String fingerprint(Object... values) {
        // Build the list manually so null optional fields such as
        // expectedVersion survive canonicalization; List.of rejects nulls.
        List<Object> parts = new ArrayList<>(values.length);
        for (Object value : values) parts.add(value);
        String canonical = canonicalJson(parts);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 不可用", error);
        }
    }

    private String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(canonicalize(value));
        } catch (Exception error) {
            throw new IllegalArgumentException("Trip 请求指纹生成失败", error);
        }
    }

    @SuppressWarnings("unchecked")
    private Object canonicalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonicalize(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonicalize).toList();
        return value;
    }

    private Map<String, Object> copyMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
        }
        return result;
    }

    private Integer integer(Object value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer requiredExpectedVersion(Map<String, Object> body) {
        if (!body.containsKey("expectedVersion")) return null;
        return integer(body.get("expectedVersion"));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String owner(String ownerId) {
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("formal Trip 必须绑定真实 Java user ID。");
        }
        return ownerId.trim();
    }

    private record WriteInput(Map<String, Object> plan, String title, Integer expectedVersion,
                             String idempotencyKey, String changeReason) {
    }

    public record OperationResult(int status, String message, String operationStatus,
                                 Trip trip, String tripId, Integer expectedVersion,
                                 Integer currentVersion, String replacementVenueId,
                                 List<String> changedSegments) {
        public OperationResult {
            changedSegments = changedSegments == null ? List.of() : List.copyOf(changedSegments);
        }

        public static OperationResult success(int status, String message, String operationStatus, Trip trip) {
            return new OperationResult(status, message, operationStatus, trip,
                    trip == null ? null : trip.id(), null,
                    trip == null ? null : trip.currentVersion(), null, List.of());
        }

        public static OperationResult success(int status, String message, String operationStatus,
                                              Trip trip, String replacementVenueId,
                                              List<String> changedSegments) {
            return new OperationResult(status, message, operationStatus, trip,
                    trip == null ? null : trip.id(), null,
                    trip == null ? null : trip.currentVersion(), replacementVenueId, changedSegments);
        }

        public static OperationResult notFound(String message) {
            return new OperationResult(404, message, "NOT_FOUND", null, null, null, null, null, List.of());
        }

        public static OperationResult badRequest(String message) {
            return new OperationResult(400, message, "BAD_REQUEST", null, null, null, null, null, List.of());
        }

        public static OperationResult deleted(String tripId, int expectedVersion) {
            return new OperationResult(200, "行程已删除", "DELETED", null,
                    tripId, expectedVersion, expectedVersion, null, List.of());
        }

        public static OperationResult conflict(String tripId, int expected, Integer current) {
            return new OperationResult(409, "行程版本已变化，请刷新后重试", "CONFLICT", null,
                    tripId, expected, current, null, List.of());
        }

        public static OperationResult idempotencyConflict(String tripId, int version) {
            return new OperationResult(409, "同一幂等键对应了不同的请求指纹", "IDEMPOTENCY_CONFLICT",
                    null, tripId, null, version, null, List.of());
        }
    }
}
