package com.ai.guide.domain.planner.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("S1 试点：StopPlan 与 AdjustmentPreview 强类型模型、边界校验与 100% 无损往返契约测试")
class StopPlanRoundTripTest {

    @Test
    @DisplayName("标准站点 Map 转为 StopPlan 后转回 Map 保持完全等价")
    void testStandardStopRoundTrip() {
        Map<String, Object> origin = new LinkedHashMap<>();
        origin.put("id", "day1-stop-1");
        origin.put("venueId", "cq-jiefangbei");
        origin.put("name", "解放碑步行街");
        origin.put("time", "09:30");
        origin.put("startTime", "09:30");
        origin.put("duration", "约 60 分钟");
        origin.put("district", "渝中区");
        origin.put("location", "106.577,29.556");
        origin.put("summary", "重庆标志性地标");
        origin.put("isPinned", true);

        StopPlan plan = StopPlan.fromMap(origin);
        assertNotNull(plan);
        assertEquals("day1-stop-1", plan.id());
        assertEquals("cq-jiefangbei", plan.venueId());
        assertEquals("解放碑步行街", plan.name());
        assertEquals("09:30", plan.time());
        assertTrue(plan.isPinned());

        Map<String, Object> converted = plan.toMap();
        assertEquals(origin, converted);
    }

    @Test
    @DisplayName("未知扩展属性（extras）无损往返保留且嵌套结构完整")
    void testExtrasLosslessPreservation() {
        Map<String, Object> origin = new LinkedHashMap<>();
        origin.put("id", "day1-added-cable");
        origin.put("venueId", "cq-cable");
        origin.put("name", "长江索道");
        origin.put("time", "15:00");
        origin.put("indoor", true);
        origin.put("walkingInfo", "从小什字地铁站5号口出直达");
        origin.put("ticket", "需购票 · 单程20元");
        origin.put("routeFromPrevious", Map.of("distanceMeters", 1200, "durationSeconds", 600));

        StopPlan plan = StopPlan.fromMap(origin);
        assertNotNull(plan);
        assertEquals(true, plan.extras().get("indoor"));
        assertEquals("从小什字地铁站5号口出直达", plan.extras().get("walkingInfo"));

        Map<String, Object> converted = plan.toMap();
        assertEquals(origin, converted);
        assertEquals(Map.of("distanceMeters", 1200, "durationSeconds", 600), converted.get("routeFromPrevious"));
    }

    @Test
    @DisplayName("核心字段优先与防伪造：直接构造时 extras 中的保留键被清洗，不可篡改核心状态")
    void testCoreFieldsOverrideMaliciousExtras() {
        // 直接构造 StopPlan 时让 time/location 为 null、isPinned=false，并向 extras 注入冲突值
        StopPlan forged = new StopPlan("s", null, null, null, null, null, null, null, null, null, null, null, false,
                Map.of("time", "25:99", "isPinned", true, "location", "999,999"));

        // extras 必须被清洗，不包含核心保留键
        assertFalse(forged.extras().containsKey("isPinned"));
        assertFalse(forged.extras().containsKey("time"));
        assertFalse(forged.extras().containsKey("location"));

        // toMap 输出必须遵循领域核心权威，不可输出 extras 中的 true 或非法时间/坐标
        assertFalse(Boolean.TRUE.equals(forged.toMap().get("isPinned")));
        assertNull(forged.toMap().get("time"));
        assertNull(forged.toMap().get("location"));
        assertEquals("s", forged.toMap().get("id"));
    }

    @Test
    @DisplayName("Wire 原生类型无损往返：保留 null、空字符串、false 与核心数值类型")
    void testWireNullEmptyFalseAndNumbersLossless() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("id", 123);
        raw.put("duration", 60);
        raw.put("time", null);
        raw.put("isPinned", false);
        raw.put("location", "");

        StopPlan plan = StopPlan.fromMap(raw);
        assertNotNull(plan);

        Map<String, Object> rt = plan.toMap();
        assertEquals(raw, rt, "StopPlan 经 fromMap -> toMap 后必须保持完全等价");
        assertInstanceOf(Integer.class, rt.get("id"), "id 数值类型不得被强转为字符串");
        assertInstanceOf(Integer.class, rt.get("duration"), "duration 数值类型不得被强转为字符串");
        assertTrue(rt.containsKey("time"), "显式 time:null 键必须保留");
        assertNull(rt.get("time"));
        assertEquals(false, rt.get("isPinned"), "isPinned:false 必须保留");
        assertEquals("", rt.get("location"), "空字符串 location:\"\" 必须保留");
    }

    @Test
    @DisplayName("AdjustmentPreview 候选提议模型无损往返（含数值与空值契约）")
    void testAdjustmentPreviewRoundTrip() {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("optionId", "a");
        preview.put("duration", 90);
        preview.put("directReplacement", false);
        preview.put("summary", null);

        AdjustmentPreview ap = AdjustmentPreview.fromMap(preview);
        assertNotNull(ap);
        assertEquals("a", ap.optionId());
        assertEquals("90", ap.duration());
        assertFalse(ap.directReplacement());

        Map<String, Object> converted = ap.toMap();
        assertEquals(preview, converted, "AdjustmentPreview 经 fromMap -> toMap 后必须保持完全等价");
        assertInstanceOf(Integer.class, converted.get("duration"));
        assertEquals(false, converted.get("directReplacement"));
        assertTrue(converted.containsKey("summary"));
        assertNull(converted.get("summary"));
    }

    @Test
    @DisplayName("AdjustmentPreview 核心字段绝对权威：extras 中的同名键被清洗")
    void testAdjustmentPreviewMaliciousExtrasSanitization() {
        AdjustmentPreview ap = new AdjustmentPreview("option-1", null, null, null, null, null, null, null, null, false,
                Map.of("directReplacement", true, "name", "stale"));

        assertFalse(ap.extras().containsKey("directReplacement"));
        assertFalse(ap.extras().containsKey("name"));
        assertFalse(Boolean.TRUE.equals(ap.toMap().get("directReplacement")));
        assertNull(ap.toMap().get("name"));
    }

    @Test
    @DisplayName("领域值不变量守卫：拒绝非法 ID、时间、负数时长与越界坐标")
    void testStopPlanValidationRejectsInvalidValues() {
        // 1. 空 ID 拒绝
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("", null, null, null, null, null, null, null, null, null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("   ", null, null, null, null, null, null, null, null, null, null, null, false, Map.of()));

        // 2. 非法时间格式拒绝（25:99、24:00）
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("valid-id", null, null, null, "25:99", null, null, null, null, null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("valid-id", null, null, null, "24:00", null, null, null, null, null, null, null, false, Map.of()));

        // 3. 负数时长拒绝（-60）
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("valid-id", null, null, null, "10:00", null, "-60", null, null, null, null, null, false, Map.of()));

        // 4. 超出合法经纬度范围坐标拒绝（999,999）
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("valid-id", null, null, null, "10:00", null, "60", null, "999,999", null, null, null, false, Map.of()));

        // 5. 合法时间与待安排允许
        assertDoesNotThrow(() ->
                new StopPlan("valid-id", null, null, null, "09:30", "09:30", "60", null, "106.577,29.556", null, null, null, false, Map.of()));
        assertDoesNotThrow(() ->
                new StopPlan("valid-id", null, null, null, "待安排", null, "约 60 分钟", null, null, null, null, null, false, Map.of()));
        assertDoesNotThrow(() ->
                new StopPlan("valid-id", null, null, null, "00:45(+1d)", null, "60", null, null, null, null, null, false, Map.of()));
    }

    @Test
    @DisplayName("缺失 ID 时转换层生成唯一稳定 ID，避免共享占位值碰撞")
    void testMissingIdGeneratesNonCollidingId() {
        StopPlan p1 = StopPlan.fromMap(Map.of());
        StopPlan p2 = StopPlan.fromMap(Map.of("name", "other"));

        assertNotNull(p1.id());
        assertNotNull(p2.id());
        assertNotEquals(p1.id(), p2.id(), "两个缺失 ID 的站点不得共享占位 ID 导致碰撞");
    }

    @Test
    @DisplayName("缺陷1验证：wireCoreValues 白名单过滤，防止快照夹带未知键与伪造核心值")
    void testWireCoreValuesSanitizationAndCoreAuthoritativeOverride() {
        // 1. StopPlan wireCoreValues 仅保留已知核心键与别名键
        Map<String, Object> maliciousWire = new LinkedHashMap<>();
        maliciousWire.put("id", "stop-99");
        maliciousWire.put("unknownKey", "malicious-payload");
        maliciousWire.put("isPinned", false);

        StopPlan plan = new StopPlan("stop-99", null, "洪崖洞", null, null, null, null, null, null, null, null, null, true,
                Map.of("extraSafe", "ok"), maliciousWire);

        // wireCoreValues 必须被白名单过滤
        assertNotNull(plan.wireCoreValues());
        assertFalse(plan.wireCoreValues().containsKey("unknownKey"), "wireCoreValues 严禁包含未知键");
        assertEquals("stop-99", plan.wireCoreValues().get("id"));

        // toMap 输出必须遵循领域核心权威（isPinned=true 覆盖 wireCoreValues 中的 false）
        Map<String, Object> out = plan.toMap();
        assertFalse(out.containsKey("unknownKey"));
        assertEquals("ok", out.get("extraSafe"));
        assertEquals(true, out.get("isPinned"), "领域核心状态 isPinned=true 权威覆盖 wireCoreValues 中的 false");

        // 2. AdjustmentPreview directReplacement 权威覆盖与未知键过滤
        Map<String, Object> previewWire = new LinkedHashMap<>();
        previewWire.put("optionId", "opt-1");
        previewWire.put("directReplacement", true);
        previewWire.put("bogusProperty", "bad");

        AdjustmentPreview preview = new AdjustmentPreview("opt-1", null, "test", null, null, null, null, null, null, false,
                Map.of(), previewWire);

        assertNotNull(preview.wireCoreValues());
        assertFalse(preview.wireCoreValues().containsKey("bogusProperty"));
        Map<String, Object> prevOut = preview.toMap();
        assertFalse(prevOut.containsKey("bogusProperty"));
        assertEquals(false, prevOut.get("directReplacement"), "领域核心状态 directReplacement=false 权威覆盖 wireCoreValues 中的 true");
    }

    @Test
    @DisplayName("缺陷2验证：别名共存与冲突时保留旧字段并延续固定规则")
    void testAliasCoexistenceAndConflictResolution() {
        // 1. id 与 stopId 共存且值一致：两者均保留
        Map<String, Object> sameId = new LinkedHashMap<>();
        sameId.put("id", "stop-same");
        sameId.put("stopId", "stop-same");
        sameId.put("name", "解放碑");
        StopPlan planSame = StopPlan.fromMap(sameId);
        assertEquals("stop-same", planSame.id());
        Map<String, Object> outSame = planSame.toMap();
        assertEquals("stop-same", outSame.get("id"));
        assertEquals("stop-same", outSame.get("stopId"));

        // 2. id 与 stopId 共存且冲突：核心 id 权威优先，且别名键保留并同步
        Map<String, Object> conflictId = new LinkedHashMap<>();
        conflictId.put("id", "stop-primary");
        conflictId.put("stopId", "stop-legacy");
        conflictId.put("name", "洪崖洞");
        StopPlan planConflict = StopPlan.fromMap(conflictId);
        assertEquals("stop-primary", planConflict.id(), "id 权威优先");
        Map<String, Object> outConflict = planConflict.toMap();
        assertEquals("stop-primary", outConflict.get("id"));
        assertEquals("stop-primary", outConflict.get("stopId"), "冲突时别名键保留并与权威 ID 保持一致");

        // 3. isPinned 与 pinned 共存且一致
        Map<String, Object> samePinned = new LinkedHashMap<>();
        samePinned.put("id", "s1");
        samePinned.put("isPinned", true);
        samePinned.put("pinned", true);
        StopPlan planPinned = StopPlan.fromMap(samePinned);
        assertTrue(planPinned.isPinned());
        Map<String, Object> outPinned = planPinned.toMap();
        assertEquals(true, outPinned.get("isPinned"));
        assertEquals(true, outPinned.get("pinned"));

        // 4. isPinned 与 pinned 冲突：延续现有规则（任一为 true 视为固定站，绝不变为非固定站）
        Map<String, Object> conflictPinned1 = new LinkedHashMap<>();
        conflictPinned1.put("id", "s2");
        conflictPinned1.put("isPinned", false);
        conflictPinned1.put("pinned", true);
        StopPlan planConflict1 = StopPlan.fromMap(conflictPinned1);
        assertTrue(planConflict1.isPinned(), "pinned=true 时必须判定为固定站");
        Map<String, Object> outConflict1 = planConflict1.toMap();
        assertEquals(true, outConflict1.get("isPinned"), "输出 isPinned 必须为 true 防止别处判定失效");
        assertEquals(true, outConflict1.get("pinned"), "输出 pinned 必须为 true 防止旧消费方判定失效");

        Map<String, Object> conflictPinned2 = new LinkedHashMap<>();
        conflictPinned2.put("id", "s3");
        conflictPinned2.put("isPinned", true);
        conflictPinned2.put("pinned", false);
        StopPlan planConflict2 = StopPlan.fromMap(conflictPinned2);
        assertTrue(planConflict2.isPinned(), "isPinned=true 时必须判定为固定站");
        Map<String, Object> outConflict2 = planConflict2.toMap();
        assertEquals(true, outConflict2.get("isPinned"));
        assertEquals(true, outConflict2.get("pinned"));

        // 5. 两者均为 false
        Map<String, Object> unpinnedBoth = new LinkedHashMap<>();
        unpinnedBoth.put("id", "s4");
        unpinnedBoth.put("isPinned", false);
        unpinnedBoth.put("pinned", false);
        StopPlan planUnpinned = StopPlan.fromMap(unpinnedBoth);
        assertFalse(planUnpinned.isPinned());
        Map<String, Object> outUnpinned = planUnpinned.toMap();
        assertEquals(false, outUnpinned.get("isPinned"));
        assertEquals(false, outUnpinned.get("pinned"));
    }

    @Test
    @DisplayName("缺陷3验证：输入 ID 为空时补生成有效 ID，输出非空且再次 round-trip 保持同一 ID")
    void testGeneratedIdIncludedInOutputAndRoundTripStable() {
        // 1. StopPlan 输入 id: null
        Map<String, Object> nullIdMap = new LinkedHashMap<>();
        nullIdMap.put("id", null);
        nullIdMap.put("name", "生成测试");
        StopPlan nullIdPlan = StopPlan.fromMap(nullIdMap);
        assertNotNull(nullIdPlan.id());
        assertFalse(nullIdPlan.id().isBlank());

        Map<String, Object> nullIdOut = nullIdPlan.toMap();
        assertNotNull(nullIdOut.get("id"), "toMap 输出必须包含生成后的有效 ID，不得输出 null");
        assertEquals(nullIdPlan.id(), nullIdOut.get("id"));

        StopPlan roundTripNull = StopPlan.fromMap(nullIdOut);
        assertEquals(nullIdPlan.id(), roundTripNull.id(), "再次转换该输出应保持同一 ID");

        // 2. StopPlan 输入 id: \"\"（空白值）
        Map<String, Object> blankIdMap = new LinkedHashMap<>();
        blankIdMap.put("id", "   ");
        blankIdMap.put("name", "空白测试");
        StopPlan blankIdPlan = StopPlan.fromMap(blankIdMap);
        Map<String, Object> blankIdOut = blankIdPlan.toMap();
        assertEquals(blankIdPlan.id(), blankIdOut.get("id"));
        assertEquals(blankIdPlan.id(), StopPlan.fromMap(blankIdOut).id());

        // 3. StopPlan 输入 id: null, stopId: null
        Map<String, Object> bothNullMap = new LinkedHashMap<>();
        bothNullMap.put("id", null);
        bothNullMap.put("stopId", null);
        StopPlan bothNullPlan = StopPlan.fromMap(bothNullMap);
        Map<String, Object> bothNullOut = bothNullPlan.toMap();
        assertEquals(bothNullPlan.id(), bothNullOut.get("id"));
        assertEquals(bothNullPlan.id(), bothNullOut.get("stopId"));
        assertEquals(bothNullPlan.id(), StopPlan.fromMap(bothNullOut).id());

        // 4. AdjustmentPreview 输入 optionId: null 或空白
        Map<String, Object> nullOptMap = new LinkedHashMap<>();
        nullOptMap.put("optionId", null);
        nullOptMap.put("name", "候选方案测试");
        AdjustmentPreview nullOpt = AdjustmentPreview.fromMap(nullOptMap);
        assertNotNull(nullOpt.optionId());
        assertFalse(nullOpt.optionId().isBlank());

        Map<String, Object> nullOptOut = nullOpt.toMap();
        assertNotNull(nullOptOut.get("optionId"), "AdjustmentPreview toMap 输出必须包含生成后的有效 optionId");
        assertEquals(nullOpt.optionId(), nullOptOut.get("optionId"));

        AdjustmentPreview roundTripOpt = AdjustmentPreview.fromMap(nullOptOut);
        assertEquals(nullOpt.optionId(), roundTripOpt.optionId(), "再次转换该输出应保持同一 optionId");

        Map<String, Object> blankOptMap = new LinkedHashMap<>();
        blankOptMap.put("optionId", "   ");
        AdjustmentPreview blankOpt = AdjustmentPreview.fromMap(blankOptMap);
        Map<String, Object> blankOptOut = blankOpt.toMap();
        assertEquals(blankOpt.optionId(), blankOptOut.get("optionId"));
        assertEquals(blankOpt.optionId(), AdjustmentPreview.fromMap(blankOptOut).optionId());
    }

    @Test
    @DisplayName("缺陷4验证：拒绝非有限坐标（NaN, Infinity, -Infinity）与越界数值，保留合法地址文本")
    void testNonFiniteCoordinatesRejectedAndAddressTextPreserved() {
        // 1. 显式非有限坐标拒绝
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "NaN,29.5", null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "106.5,NaN", null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "Infinity,29.5", null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "-Infinity,29.5", null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "106.5,Infinity", null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "106.5,-Infinity", null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "NaN", null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "Infinity", null, null, null, false, Map.of()));

        // 2. 越界经纬度数值拒绝
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "181.0,29.5", null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "-181.0,29.5", null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "106.5,91.0", null, null, null, false, Map.of()));
        assertThrows(IllegalArgumentException.class, () ->
                new StopPlan("s", null, null, null, null, null, null, null, "106.5,-91.0", null, null, null, false, Map.of()));

        // 3. 正常普通地址文本（含逗号）保留兼容
        assertDoesNotThrow(() ->
                new StopPlan("s", null, null, null, null, null, null, null, "解放碑步行街, 渝中区", null, null, null, false, Map.of()));
        assertDoesNotThrow(() ->
                new StopPlan("s", null, null, null, null, null, null, null, "洪崖洞民俗风貌区, 嘉陵江滨江路88号", null, null, null, false, Map.of()));
        assertDoesNotThrow(() ->
                new StopPlan("s", null, null, null, null, null, null, null, "待安排", null, null, null, false, Map.of()));
        assertDoesNotThrow(() ->
                new StopPlan("s", null, null, null, null, null, null, null, "未知位置", null, null, null, false, Map.of()));
        assertDoesNotThrow(() ->
                new StopPlan("s", null, null, null, null, null, null, null, "", null, null, null, false, Map.of()));
        assertDoesNotThrow(() ->
                new StopPlan("s", null, null, null, null, null, null, null, null, null, null, null, false, Map.of()));
    }
}
