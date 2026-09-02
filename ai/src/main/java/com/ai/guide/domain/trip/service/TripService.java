package com.ai.guide.domain.trip.service;

import com.ai.guide.domain.planner.model.PlannerSession;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import com.ai.guide.domain.trip.repository.TripRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private final TripRepository repository;
    private final TripReplanEngine replanEngine;
    private final ObjectMapper objectMapper;

    public TripService(TripRepository repository, TripReplanEngine replanEngine,
                       ObjectMapper objectMapper) {
        this.repository = repository;
        this.replanEngine = replanEngine;
        this.objectMapper = objectMapper;
    }

    public List<Trip> list(String ownerId) {
        return repository.list(owner(ownerId));
    }

    public Trip get(String ownerId, String tripId) {
        return repository.find(owner(ownerId), tripId);
    }

    @Transactional
    public OperationResult create(String ownerId, Map<String, Object> request) {
        String effectiveOwner = owner(ownerId);
        WriteInput input = parseWrite(request);
        String fingerprint = fingerprint("CREATE", input.title(), input.plan(), input.idempotencyKey());
        OperationResult replay = replay(effectiveOwner, "CREATE", input.idempotencyKey(), fingerprint);
        if (replay != null) return replay;

        String tripId = "trip-" + UUID.randomUUID();
        Map<String, Object> snapshot = withFormalId(input.plan(), tripId);
        String title = title(input.title(), snapshot);
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
        WriteInput input = parseWrite(body);
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
        int updated = repository.advanceCurrentVersion(effectiveOwner, tripId, expected,
                nextVersion, title, now);
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
        Map<String, Object> snapshot = copyMap(plan);
        String formalTripId = text(snapshot.get("formalTripId"));
        String title = text(snapshot.get("title"));
        String key = text(idempotencyKey);
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
        Integer sourceTripVersion = sourceTripVersion(snapshot.get("sourceTripVersion"));
        int expectedVersion = sourceTripVersion == null ? current.currentVersion() : sourceTripVersion;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("plan", snapshot);
        request.put("title", title.isBlank() ? current.title() : title);
        request.put("expectedVersion", expectedVersion);
        request.put("idempotencyKey", key);
        request.put("changeReason", "PlannerSession 保存");
        return update(ownerId, formalTripId, request);
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
        TripRepository.IdempotencyRecord record = repository.findIdempotency(ownerId, operation, key);
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
