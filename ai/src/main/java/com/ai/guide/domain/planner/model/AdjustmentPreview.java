package com.ai.guide.domain.planner.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 强类型调整提议候选方案值对象 (AdjustmentPreview Value Object).
 * 承载单选项的展示信息、难度、标签及扩展属性，并提供与 Map 结构的无损往返转换。
 * 遵循核心字段绝对权威原则：wireCoreValues 与 extras 不得覆盖已校验的核心语义。
 */
public record AdjustmentPreview(
        String optionId,
        String venueId,
        String name,
        String requestedName,
        String summary,
        String duration,
        String district,
        String walkDifficulty,
        String fit,
        boolean directReplacement,
        Map<String, Object> extras,
        Map<String, Object> wireCoreValues
) {

    public static final Set<String> CORE_KEYS = Set.of(
            "optionId", "venueId", "name", "requestedName", "summary",
            "duration", "district", "walkDifficulty", "fit", "directReplacement"
    );

    public AdjustmentPreview {
        if (optionId == null || optionId.isBlank()) {
            throw new IllegalArgumentException("optionId 不能为空或空白");
        }

        // 核心字段绝对权威原则：extras 强制剔除所有核心键，防止绕过领域值
        Map<String, Object> cleanExtras = new LinkedHashMap<>();
        if (extras != null) {
            for (Map.Entry<String, Object> entry : extras.entrySet()) {
                if (!CORE_KEYS.contains(entry.getKey())) {
                    cleanExtras.put(entry.getKey(), entry.getValue());
                }
            }
        }
        extras = Collections.unmodifiableMap(cleanExtras);

        // wireCoreValues 仅保留已知核心键，防止快照夹带任意键
        Map<String, Object> cleanWire = null;
        if (wireCoreValues != null) {
            cleanWire = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : wireCoreValues.entrySet()) {
                if (CORE_KEYS.contains(entry.getKey())) {
                    cleanWire.put(entry.getKey(), entry.getValue());
                }
            }
            cleanWire = Collections.unmodifiableMap(cleanWire);
        }
        wireCoreValues = cleanWire;
    }

    /**
     * 向后兼容的 11 参数构造函数（供外部及测试直接构造，无 wireCoreValues 快照）。
     */
    public AdjustmentPreview(
            String optionId,
            String venueId,
            String name,
            String requestedName,
            String summary,
            String duration,
            String district,
            String walkDifficulty,
            String fit,
            boolean directReplacement,
            Map<String, Object> extras
    ) {
        this(optionId, venueId, name, requestedName, summary, duration, district, walkDifficulty, fit, directReplacement, extras, null);
    }

    public static AdjustmentPreview fromMap(Map<String, Object> map) {
        if (map == null) return null;

        String rawOptionId = map.get("optionId") != null ? String.valueOf(map.get("optionId")).trim() : "";
        String optionId = rawOptionId.isBlank() ? "option-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) : rawOptionId;

        String venueId = map.get("venueId") != null ? String.valueOf(map.get("venueId")) : null;
        String name = map.get("name") != null ? String.valueOf(map.get("name")) : null;
        String requestedName = map.get("requestedName") != null ? String.valueOf(map.get("requestedName")) : null;
        String summary = map.get("summary") != null ? String.valueOf(map.get("summary")) : null;
        String duration = map.get("duration") != null ? String.valueOf(map.get("duration")) : null;
        String district = map.get("district") != null ? String.valueOf(map.get("district")) : null;
        String walkDifficulty = map.get("walkDifficulty") != null ? String.valueOf(map.get("walkDifficulty")) : null;
        String fit = map.get("fit") != null ? String.valueOf(map.get("fit")) : null;
        boolean directReplacement = Boolean.TRUE.equals(map.get("directReplacement"));

        Map<String, Object> extraAttrs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!CORE_KEYS.contains(entry.getKey())) {
                extraAttrs.put(entry.getKey(), entry.getValue());
            }
        }

        Map<String, Object> wireCore = new LinkedHashMap<>();
        for (String coreKey : CORE_KEYS) {
            if (map.containsKey(coreKey)) {
                wireCore.put(coreKey, map.get(coreKey));
            }
        }

        return new AdjustmentPreview(optionId, venueId, name, requestedName, summary,
                duration, district, walkDifficulty, fit, directReplacement, extraAttrs, wireCore);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (extras != null && !extras.isEmpty()) {
            out.putAll(extras);
        }

        if (wireCoreValues != null && !wireCoreValues.isEmpty()) {
            out.putAll(wireCoreValues);

            // 1. optionId 权威同步：原输入为 null/空白（补生成了有效 optionId）或与核心语义不一致，必须输出有效 optionId
            Object rawOptionId = wireCoreValues.get("optionId");
            if (rawOptionId == null || String.valueOf(rawOptionId).isBlank() || !String.valueOf(rawOptionId).trim().equals(optionId)) {
                out.put("optionId", optionId);
            }

            // 2. directReplacement 权威同步：输出与类型对象的核心语义一致
            if (directReplacement) {
                out.put("directReplacement", true);
            } else {
                if (wireCoreValues.containsKey("directReplacement")) {
                    out.put("directReplacement", false);
                }
            }

            // 3. duration 同步：若数值匹配则保留原始对象类型，否则输出规范字符串
            if (duration == null) {
                if (wireCoreValues.containsKey("duration") && wireCoreValues.get("duration") == null) {
                    out.put("duration", null);
                } else {
                    out.remove("duration");
                }
            } else {
                Object rawDur = wireCoreValues.get("duration");
                if (rawDur != null && String.valueOf(rawDur).trim().equals(duration)) {
                    out.put("duration", rawDur);
                } else {
                    out.put("duration", duration);
                }
            }

            // 4. 其余核心字段同步
            syncCoreStringField(out, "venueId", venueId);
            syncCoreStringField(out, "name", name);
            syncCoreStringField(out, "requestedName", requestedName);
            syncCoreStringField(out, "summary", summary);
            syncCoreStringField(out, "district", district);
            syncCoreStringField(out, "walkDifficulty", walkDifficulty);
            syncCoreStringField(out, "fit", fit);

        } else {
            out.put("optionId", optionId);
            if (venueId != null) out.put("venueId", venueId);
            if (name != null) out.put("name", name);
            if (requestedName != null) out.put("requestedName", requestedName);
            if (summary != null) out.put("summary", summary);
            if (duration != null) out.put("duration", duration);
            if (district != null) out.put("district", district);
            if (walkDifficulty != null) out.put("walkDifficulty", walkDifficulty);
            if (fit != null) out.put("fit", fit);
            if (directReplacement) out.put("directReplacement", true);
        }

        return out;
    }

    private void syncCoreStringField(Map<String, Object> out, String fieldName, String coreValue) {
        if (coreValue == null) {
            if (wireCoreValues.containsKey(fieldName) && wireCoreValues.get(fieldName) == null) {
                out.put(fieldName, null);
            } else {
                out.remove(fieldName);
            }
        } else {
            out.put(fieldName, coreValue);
        }
    }
}
