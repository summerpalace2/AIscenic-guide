package com.ai.guide.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TripPlan 存储/API 边界。
 *
 * payload 有意保持不透明：此适配器只维护 Web 契约，不会将旧 Knowledge/Qdrant
 * 值提升为已验证的产品事实。
 */
@Service
public class TripPlanService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TripPlanService(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> list(String userId) {
        return jdbcTemplate.query(
                "SELECT id, payload, created_at, updated_at FROM trip_plan WHERE user_id = ? ORDER BY updated_at DESC",
                (rs, rowNum) -> hydrate(rs.getString("id"), rs.getString("payload"),
                        rs.getLong("created_at"), rs.getLong("updated_at")),
                userId);
    }

    public Map<String, Object> get(String userId, String tripId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, payload, created_at, updated_at FROM trip_plan WHERE id = ? AND user_id = ?",
                    (rs, rowNum) -> hydrate(rs.getString("id"), rs.getString("payload"),
                            rs.getLong("created_at"), rs.getLong("updated_at")),
                    tripId, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public Map<String, Object> save(String userId, Map<String, Object> input) {
        Map<String, Object> plan = normalize(input);
        String tripId = text(plan.get("tripId"));
        if (tripId.isBlank()) {
            tripId = "trip-" + UUID.randomUUID();
            plan.put("tripId", tripId);
        }

        long now = System.currentTimeMillis();
        String payload = writeJson(plan);
        int updated = jdbcTemplate.update(
                "UPDATE trip_plan SET payload = ?, updated_at = ? WHERE id = ? AND user_id = ?",
                payload, now, tripId, userId);
        if (updated == 0) {
            jdbcTemplate.update(
                    "INSERT INTO trip_plan (id, user_id, payload, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    tripId, userId, payload, now, now);
        }
        return get(userId, tripId);
    }

    public boolean delete(String userId, String tripId) {
        return jdbcTemplate.update("DELETE FROM trip_plan WHERE id = ? AND user_id = ?", tripId, userId) > 0;
    }

    /**
     * 保存客户端提交的完整行程。由客户端负责发送未修改的天数和景点；此方法
     * 永远不会虚构替代景点。
     */
    public Map<String, Object> replan(String userId, String tripId, Map<String, Object> request) {
        Map<String, Object> plan = request == null ? new LinkedHashMap<>() : asMap(request.get("plan"));
        if (plan.isEmpty()) {
            plan = get(userId, tripId);
            if (plan == null) return null;
        } else {
            plan.put("tripId", tripId);
        }

        String reason = text(request == null ? null : request.get("reason"));
        String entityId = text(request == null ? null : request.get("entityId"));
        if (!reason.isBlank()) plan.put("lastReplanReason", reason);
        if (!entityId.isBlank()) plan.put("lastReplannedEntityId", entityId);
        plan.put("status", "SAVED");
        return save(userId, plan);
    }

    /**
     * 仅返回由行程数据支撑的投影。不执行独立知识查询，因此缺少权威详情时
     * 保持 404，而不是伪造来源。
     */
    public Map<String, Object> findAttraction(String userId, String entityId) {
        for (Map<String, Object> plan : list(userId)) {
            Object daysValue = plan.get("days");
            if (!(daysValue instanceof List<?> days)) continue;
            for (Object dayValue : days) {
                if (!(dayValue instanceof Map<?, ?> day)) continue;
                Object stopsValue = day.get("stops");
                if (!(stopsValue instanceof List<?> stops)) continue;
                for (Object stopValue : stops) {
                    if (!(stopValue instanceof Map<?, ?> stop)) continue;
                    String candidate = text(stop.get("entityId"));
                    if (!entityId.equals(candidate)) continue;
                    Map<String, Object> detail = new LinkedHashMap<>();
                    stop.forEach((key, value) -> detail.put(String.valueOf(key), value));
                    detail.putIfAbsent("dataStatus", text(plan.get("dataStatus")).isBlank()
                            ? "UNVERIFIED" : plan.get("dataStatus"));
                    detail.putIfAbsent("factStatus", "UNKNOWN");
                    detail.putIfAbsent("citations", new ArrayList<>());
                    return detail;
                }
            }
        }
        return null;
    }

    private Map<String, Object> normalize(Map<String, Object> input) {
        Map<String, Object> plan = new LinkedHashMap<>();
        if (input != null) plan.putAll(input);
        plan.putIfAbsent("status", "DRAFT");
        // 这是契约标签，不代表任何单独字段的事实声明。
        plan.putIfAbsent("dataStatus", "UNVERIFIED");
        plan.putIfAbsent("citations", new ArrayList<>());
        return plan;
    }

    private Map<String, Object> hydrate(String id, String payload, long createdAt, long updatedAt) {
        Map<String, Object> plan;
        try {
            plan = objectMapper.readValue(payload == null ? "{}" : payload, MAP_TYPE);
        } catch (Exception e) {
            plan = new LinkedHashMap<>();
            plan.put("dataStatus", "UNVERIFIED");
        }
        plan.put("tripId", id);
        plan.put("createdAt", createdAt);
        plan.put("updatedAt", updatedAt);
        plan.putIfAbsent("dataStatus", "UNVERIFIED");
        plan.putIfAbsent("citations", new ArrayList<>());
        return plan;
    }

    private String writeJson(Map<String, Object> plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            throw new IllegalArgumentException("行程数据格式无效", e);
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
