package com.ai.guide.domain.planner.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 强类型站点领域值对象 (StopPlan Value Object).
 * 封装单站时间、地点、分类与保护标记；
 * 严格管理 extras 扩展字段，保证与历史 Map 结构的无损双向转换，
 * 且遵循核心字段优先原则（extras 与 wireCoreValues 不得覆盖已校验的核心语义）。
 */
public record StopPlan(
        String id,
        String venueId,
        String name,
        String displayName,
        String time,
        String startTime,
        String duration,
        String district,
        String location,
        String type,
        String summary,
        String intro,
        boolean isPinned,
        Map<String, Object> extras,
        Map<String, Object> wireCoreValues
) {

    public static final Set<String> CORE_FIELD_KEYS = Set.of(
            "id", "venueId", "name", "displayName", "time", "startTime",
            "duration", "district", "location", "type", "summary", "intro", "isPinned"
    );

    public static final Set<String> ALIAS_KEYS = Set.of("stopId", "pinned");

    public static final Set<String> KNOWN_WIRE_KEYS = Set.of(
            "id", "stopId", "venueId", "name", "displayName", "time", "startTime",
            "duration", "district", "location", "type", "summary", "intro", "isPinned", "pinned"
    );

    private static final Pattern TIME_PATTERN = Pattern.compile("^([01]\\d|2[0-3]):[0-5]\\d(\\(\\+1d\\))?$");

    public StopPlan {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("stop id 不能为空或空白");
        }
        validateTime(time, "time");
        validateTime(startTime, "startTime");
        validateDuration(duration);
        validateLocation(location);

        // 1. extras 严禁包含核心键与已知别名键，防止绕过领域值
        Map<String, Object> cleanExtras = new LinkedHashMap<>();
        if (extras != null) {
            for (Map.Entry<String, Object> entry : extras.entrySet()) {
                if (!KNOWN_WIRE_KEYS.contains(entry.getKey())) {
                    cleanExtras.put(entry.getKey(), entry.getValue());
                }
            }
        }
        extras = Collections.unmodifiableMap(cleanExtras);

        // 2. wireCoreValues 仅保留已知核心与别名键，防止快照夹带任意键
        Map<String, Object> cleanWire = null;
        if (wireCoreValues != null) {
            cleanWire = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : wireCoreValues.entrySet()) {
                if (KNOWN_WIRE_KEYS.contains(entry.getKey())) {
                    cleanWire.put(entry.getKey(), entry.getValue());
                }
            }
            cleanWire = Collections.unmodifiableMap(cleanWire);
        }
        wireCoreValues = cleanWire;
    }

    /**
     * 向后兼容的 14 参数构造函数（供外部及测试直接构造，无 wireCoreValues 快照）。
     */
    public StopPlan(
            String id,
            String venueId,
            String name,
            String displayName,
            String time,
            String startTime,
            String duration,
            String district,
            String location,
            String type,
            String summary,
            String intro,
            boolean isPinned,
            Map<String, Object> extras
    ) {
        this(id, venueId, name, displayName, time, startTime, duration, district, location, type, summary, intro, isPinned, extras, null);
    }

    /**
     * 从历史 Map 结构构造强类型 StopPlan，无损捕获非核心扩展字段与核心字段的 Wire 原始类型。
     */
    public static StopPlan fromMap(Map<String, Object> map) {
        if (map == null) return null;

        // 1. 规范化提取用于领域校验的核心属性
        String rawIdStr = map.get("id") != null ? String.valueOf(map.get("id")).trim() : "";
        String rawStopIdStr = map.get("stopId") != null ? String.valueOf(map.get("stopId")).trim() : "";

        String effectiveId;
        if (!rawIdStr.isBlank()) {
            effectiveId = rawIdStr;
        } else if (!rawStopIdStr.isBlank()) {
            effectiveId = rawStopIdStr;
        } else {
            // 缺稳定 ID 时由转换层生成唯一且不碰撞的 ID，避免共享占位值
            effectiveId = "gen-stop-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }

        String venueId = map.get("venueId") != null ? String.valueOf(map.get("venueId")) : null;
        String name = map.get("name") != null ? String.valueOf(map.get("name")) : null;
        String displayName = map.get("displayName") != null ? String.valueOf(map.get("displayName")) : null;
        String time = map.get("time") != null ? String.valueOf(map.get("time")) : null;
        String startTime = map.get("startTime") != null ? String.valueOf(map.get("startTime")) : null;
        String duration = map.get("duration") != null ? String.valueOf(map.get("duration")) : null;
        String district = map.get("district") != null ? String.valueOf(map.get("district")) : null;
        String location = map.get("location") != null ? String.valueOf(map.get("location")) : null;
        String type = map.get("type") != null ? String.valueOf(map.get("type")) : null;
        String summary = map.get("summary") != null ? String.valueOf(map.get("summary")) : null;
        String intro = map.get("intro") != null ? String.valueOf(map.get("intro")) : null;

        // 延续现有固定标记的解读规则：isPinned 为 true 或 pinned 为 true 即视为固定站
        boolean isPinned = Boolean.TRUE.equals(map.get("isPinned")) || Boolean.TRUE.equals(map.get("pinned"));

        // 2. 提取 extras 扩展属性（非核心且非别名键）
        Map<String, Object> extraAttrs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!KNOWN_WIRE_KEYS.contains(entry.getKey())) {
                extraAttrs.put(entry.getKey(), entry.getValue());
            }
        }

        // 3. 记录 Wire 契约原始核心字段与别名键值（保留 null、空字符串、false 及原始数值类型）
        Map<String, Object> wireCore = new LinkedHashMap<>();
        for (String key : KNOWN_WIRE_KEYS) {
            if (map.containsKey(key)) {
                wireCore.put(key, map.get(key));
            }
        }

        return new StopPlan(effectiveId, venueId, name, displayName, time, startTime,
                duration, district, location, type, summary, intro, isPinned, extraAttrs, wireCore);
    }

    /**
     * 将强类型 StopPlan 序列化为 Map 结构。
     * 遵循契约：
     * 1. 输出与类型对象的核心语义严格一致，不允许快照覆盖已校验核心字段；
     * 2. 保留输入中的合法数值类型、显式 null、false、空字符串；
     * 3. 保留已知别名字段（stopId, pinned）；
     * 4. 补生成 ID 必须反映在输出中，再次转换保持同一有效 ID。
     */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (extras != null && !extras.isEmpty()) {
            out.putAll(extras);
        }

        if (wireCoreValues != null && !wireCoreValues.isEmpty()) {
            out.putAll(wireCoreValues);

            // 1. ID 与 stopId 权威同步
            if (wireCoreValues.containsKey("id")) {
                Object rawId = wireCoreValues.get("id");
                if (rawId == null || String.valueOf(rawId).isBlank() || !String.valueOf(rawId).trim().equals(id)) {
                    out.put("id", id);
                }
            } else if (!wireCoreValues.containsKey("stopId")) {
                out.put("id", id);
            }

            if (wireCoreValues.containsKey("stopId")) {
                Object rawStopId = wireCoreValues.get("stopId");
                if (rawStopId == null || String.valueOf(rawStopId).isBlank()) {
                    out.put("stopId", id);
                    if (!wireCoreValues.containsKey("id")) {
                        out.put("id", id);
                    }
                } else if (!String.valueOf(rawStopId).trim().equals(id)) {
                    out.put("stopId", id);
                }
            }

            // 2. isPinned 与 pinned 权威同步
            if (isPinned) {
                if (wireCoreValues.containsKey("isPinned") || !wireCoreValues.containsKey("pinned")) {
                    out.put("isPinned", true);
                }
                if (wireCoreValues.containsKey("pinned")) {
                    out.put("pinned", true);
                }
            } else {
                if (wireCoreValues.containsKey("isPinned")) {
                    out.put("isPinned", false);
                }
                if (wireCoreValues.containsKey("pinned")) {
                    out.put("pinned", false);
                }
            }

            // 3. time 与 startTime 权威同步
            syncCoreStringField(out, "time", time);
            syncCoreStringField(out, "startTime", startTime);

            // 4. location 权威同步
            syncCoreStringField(out, "location", location);

            // 5. duration 同步：若数值匹配则保留原始对象类型，否则输出规范字符串
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

            // 6. 其余核心字段同步
            syncCoreStringField(out, "venueId", venueId);
            syncCoreStringField(out, "name", name);
            syncCoreStringField(out, "displayName", displayName);
            syncCoreStringField(out, "district", district);
            syncCoreStringField(out, "type", type);
            syncCoreStringField(out, "summary", summary);
            syncCoreStringField(out, "intro", intro);

        } else {
            // 直接构造路径：落实核心权威状态
            out.put("id", id);
            if (venueId != null) out.put("venueId", venueId);
            if (name != null) out.put("name", name);
            if (displayName != null) out.put("displayName", displayName);
            if (time != null) out.put("time", time);
            if (startTime != null) out.put("startTime", startTime);
            if (duration != null) out.put("duration", duration);
            if (district != null) out.put("district", district);
            if (location != null) out.put("location", location);
            if (type != null) out.put("type", type);
            if (summary != null) out.put("summary", summary);
            if (intro != null) out.put("intro", intro);
            if (isPinned) out.put("isPinned", true);
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

    private static void validateTime(String time, String fieldName) {
        if (time == null || time.isBlank() || time.equals("待安排")) return;
        if (!TIME_PATTERN.matcher(time.trim()).matches()) {
            throw new IllegalArgumentException("非法" + fieldName + "时间格式: " + time);
        }
    }

    private static void validateDuration(String duration) {
        if (duration == null || duration.isBlank()) return;
        if (duration.trim().startsWith("-")) {
            throw new IllegalArgumentException("游览时长不能为负数: " + duration);
        }
    }

    private static void validateLocation(String location) {
        if (location == null || location.isBlank()) return;
        String trimmed = location.trim();
        if (trimmed.equalsIgnoreCase("nan") || trimmed.equalsIgnoreCase("infinity") || trimmed.equalsIgnoreCase("-infinity")) {
            throw new IllegalArgumentException("坐标必须为有限数值: " + location);
        }
        if (location.contains(",")) {
            String[] parts = location.split(",");
            if (parts.length == 2) {
                String p0 = parts[0].trim();
                String p1 = parts[1].trim();
                boolean hasSpecialFloatingLiteral =
                        p0.equalsIgnoreCase("nan") || p0.equalsIgnoreCase("infinity") || p0.equalsIgnoreCase("-infinity") ||
                        p1.equalsIgnoreCase("nan") || p1.equalsIgnoreCase("infinity") || p1.equalsIgnoreCase("-infinity");
                try {
                    double lng = Double.parseDouble(p0);
                    double lat = Double.parseDouble(p1);
                    if (!Double.isFinite(lng) || !Double.isFinite(lat)) {
                        throw new IllegalArgumentException("坐标必须为有限数值: " + location);
                    }
                    if (lng < -180 || lng > 180 || lat < -90 || lat > 90) {
                        throw new IllegalArgumentException("坐标超出合法经纬度范围: " + location);
                    }
                } catch (NumberFormatException e) {
                    if (hasSpecialFloatingLiteral) {
                        throw new IllegalArgumentException("坐标必须为有限数值: " + location);
                    }
                    // 保留非经纬度的普通地址文本（例如 "某步行街,某区"）
                }
            }
        }
    }
}
