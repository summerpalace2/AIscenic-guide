package com.ai.guide.domain.planner.model;

import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 行程规划实际应用的偏好快照模型
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 * 架构职责：记录本次规划决策实际采纳的用户偏好字段、偏好版本号及偏好指纹，支撑决策溯源。
 */
public final class AppliedPreferencesSnapshot {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final Set<String> ROOT_FIELDS = Set.of(
            "appliedFields", "preferenceRevision", "preferenceSchemaVersion",
            "normalizationVersion", "fingerprint");
    private static final Set<String> ENUM_FIELDS = Set.of(
            "walkingTolerance", "companions", "transportPreference", "dietPreference");
    private static final Set<String> WALKING = Set.of("UNSPECIFIED", "LOW", "NORMAL", "HIGH");
    private static final Set<String> COMPANIONS = Set.of(
            "UNSPECIFIED", "SOLO", "COUPLE", "FAMILY", "FRIENDS", "PARENTS", "CHILDREN", "GROUP");
    private static final Set<String> TRANSPORT = Set.of(
            "UNSPECIFIED", "PUBLIC_TRANSIT", "WALKING", "DRIVING", "TAXI", "BICYCLE", "MIXED");
    private static final Set<String> DIET = Set.of(
            "UNSPECIFIED", "NONE", "VEGETARIAN", "VEGAN", "HALAL", "KOSHER", "ALLERGY");
    private static final Set<String> BUDGET_LEVELS = Set.of(
            "UNSPECIFIED", "LIMITED", "LOW", "MEDIUM", "HIGH", "AMOUNT");

    private final Map<String, Object> appliedFields;
    private final long preferenceRevision;
    private final int preferenceSchemaVersion;
    private final String normalizationVersion;
    private final String fingerprint;

    private AppliedPreferencesSnapshot(Map<String, Object> appliedFields,
                                       long preferenceRevision,
                                       int preferenceSchemaVersion,
                                       String normalizationVersion,
                                       String fingerprint) {
        this.appliedFields = deepImmutableMap(appliedFields);
        this.preferenceRevision = preferenceRevision;
        this.preferenceSchemaVersion = preferenceSchemaVersion;
        this.normalizationVersion = normalizationVersion;
        this.fingerprint = fingerprint;
    }

    public static AppliedPreferencesSnapshot empty() {
        return create(Map.of(), 0, PreferencesSchema.CURRENT_SCHEMA_VERSION,
                PreferencesSchema.NORMALIZATION_VERSION, new ObjectMapper());
    }

    public static AppliedPreferencesSnapshot create(Map<String, Object> fields,
                                                     long preferenceRevision,
                                                     int preferenceSchemaVersion,
                                                     String normalizationVersion,
                                                     ObjectMapper objectMapper) {
        if (preferenceRevision < 0) throw new IllegalArgumentException("偏好快照 revision 不能为负数");
        if (preferenceSchemaVersion < 1) throw new IllegalArgumentException("偏好快照 schema 无效");
        if (normalizationVersion == null || normalizationVersion.isBlank()) {
            throw new IllegalArgumentException("偏好快照 normalizationVersion 不能为空");
        }
        ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        Map<String, Object> canonical = canonicalFields(fields, false);
        String fingerprint = fingerprint(canonical, preferenceRevision, preferenceSchemaVersion,
                normalizationVersion, mapper);
        return new AppliedPreferencesSnapshot(canonical, preferenceRevision, preferenceSchemaVersion,
                normalizationVersion, fingerprint);
    }

    /** Safely decodes a persisted value; legacy arrays and malformed values become empty. */
    public static AppliedPreferencesSnapshot fromJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank()) return empty();
        try {
            ObjectMapper mapper = objectMapper == null ? new ObjectMapper() : objectMapper;
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) return empty();
            Set<String> names = new LinkedHashSet<>();
            root.fieldNames().forEachRemaining(names::add);
            if (!ROOT_FIELDS.containsAll(names) || !root.has("appliedFields")
                    || !root.get("appliedFields").isObject()) return empty();
            JsonNode revisionNode = root.get("preferenceRevision");
            JsonNode schemaNode = root.get("preferenceSchemaVersion");
            JsonNode normalizationNode = root.get("normalizationVersion");
            JsonNode fingerprintNode = root.get("fingerprint");
            if (revisionNode == null || !revisionNode.isIntegralNumber()
                    || schemaNode == null || !schemaNode.isIntegralNumber()
                    || normalizationNode == null || !normalizationNode.isTextual()
                    || fingerprintNode == null || !fingerprintNode.isTextual()) return empty();
            long revision = revisionNode.longValue();
            int schema = schemaNode.intValue();
            String normalization = normalizationNode.textValue();
            if (revision < 0 || schema != PreferencesSchema.CURRENT_SCHEMA_VERSION
                    || !PreferencesSchema.NORMALIZATION_VERSION.equals(normalization)) return empty();
            Map<String, Object> fields = mapper.convertValue(root.get("appliedFields"), MAP_TYPE);
            Map<String, Object> canonical = canonicalFields(fields, true);
            if (canonical.size() != fields.size()) return empty();
            AppliedPreferencesSnapshot candidate = create(canonical, revision, schema, normalization, mapper);
            return candidate.fingerprint.equals(fingerprintNode.textValue()) ? candidate : empty();
        } catch (Exception ignored) {
            return empty();
        }
    }

    public Map<String, Object> appliedFields() {
        return appliedFields;
    }

    public long preferenceRevision() {
        return preferenceRevision;
    }

    public int preferenceSchemaVersion() {
        return preferenceSchemaVersion;
    }

    public String normalizationVersion() {
        return normalizationVersion;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public boolean isEmpty() {
        return appliedFields.isEmpty();
    }

    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("appliedFields", appliedFields);
        result.put("preferenceRevision", preferenceRevision);
        result.put("preferenceSchemaVersion", preferenceSchemaVersion);
        result.put("normalizationVersion", normalizationVersion);
        result.put("fingerprint", fingerprint);
        return result;
    }

    private static Map<String, Object> canonicalFields(Map<String, Object> values, boolean strict) {
        Map<String, Object> source = values == null ? Map.of() : values;
        if (strict) {
            for (String key : source.keySet()) {
                if (!PreferencesSchema.CANONICAL_FIELDS.contains(key)) return Map.of();
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : PreferencesSchema.CANONICAL_FIELDS) {
            if (!source.containsKey(field)) continue;
            Object normalized = normalizeField(field, source.get(field));
            if (normalized != null) result.put(field, normalized);
            else if (strict) return Map.of();
        }
        return result;
    }

    private static Object normalizeField(String field, Object value) {
        if (value == null) return null;
        if ("interests".equals(field)) {
            if (!(value instanceof List<?> list)) return null;
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item == null) continue;
                String text = String.valueOf(item).trim();
                if (!text.isBlank() && !result.contains(text)) result.add(text);
            }
            return result.isEmpty() ? null : List.copyOf(result);
        }
        if (ENUM_FIELDS.contains(field)) {
            String text = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
            Set<String> allowed = switch (field) {
                case "walkingTolerance" -> WALKING;
                case "companions" -> COMPANIONS;
                case "transportPreference" -> TRANSPORT;
                case "dietPreference" -> DIET;
                default -> Set.of();
            };
            return allowed.contains(text) && !"UNSPECIFIED".equals(text) ? text : null;
        }
        if ("budget".equals(field)) return normalizeBudget(value);
        if ("stayArea".equals(field)) {
            String text = String.valueOf(value).trim();
            return text.isBlank() ? null : text;
        }
        return null;
    }

    private static Object normalizeBudget(Object value) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        Set<String> allowed = Set.of("level", "maxAmount", "currency");
        Map<String, Object> source = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) source.put(String.valueOf(entry.getKey()), entry.getValue());
        for (String key : source.keySet()) if (!allowed.contains(key)) return null;
        String level = source.get("level") == null ? "" : String.valueOf(source.get("level")).trim().toUpperCase(Locale.ROOT);
        if (!BUDGET_LEVELS.contains(level) || "UNSPECIFIED".equals(level)) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("level", level);
        if (source.containsKey("maxAmount") && source.get("maxAmount") != null) {
            String amount = String.valueOf(source.get("maxAmount")).trim();
            if (amount.isBlank()) return null;
            result.put("maxAmount", amount);
        }
        if (source.containsKey("currency") && source.get("currency") != null) {
            String currency = String.valueOf(source.get("currency")).trim().toUpperCase(Locale.ROOT);
            if (!currency.isBlank()) result.put("currency", currency);
        }
        if ("AMOUNT".equals(level) && !result.containsKey("maxAmount")) return null;
        return result;
    }

    private static String fingerprint(Map<String, Object> fields, long revision, int schema,
                                      String normalization, ObjectMapper mapper) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("appliedFields", fields);
            payload.put("preferenceRevision", revision);
            payload.put("preferenceSchemaVersion", schema);
            payload.put("normalizationVersion", normalization);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("无法生成偏好快照指纹", error);
        }
    }

    private static Map<String, Object> deepImmutableMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source != null) {
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> raw) {
                    Map<String, Object> nested = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> nestedEntry : raw.entrySet()) {
                        nested.put(String.valueOf(nestedEntry.getKey()), nestedEntry.getValue());
                    }
                    result.put(entry.getKey(), Map.copyOf(nested));
                } else if (value instanceof List<?> list) {
                    result.put(entry.getKey(), List.copyOf(list));
                } else {
                    result.put(entry.getKey(), value);
                }
            }
        }
        return Map.copyOf(result);
    }
}
