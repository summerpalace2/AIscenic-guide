package com.ai.guide.application.agent.tools;

import com.ai.guide.application.agent.AgentTripAuthoringService;
import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.application.preferences.PreferencesApplicationService;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.domain.preferences.model.BudgetPreference;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.service.PreferenceConflictException;
import com.ai.guide.domain.preferences.service.PreferencesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Phase 2 规划草稿读写、沙箱预演、HIL 应用与个人偏好工具单元测试。
 */
class AgentPlannerAndPreferenceToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AgentExecutionContext UNBOUND_GUEST_CTX =
            new AgentExecutionContext("", true, "s-guest", "", "", "", null, "重庆", null, "", "", false);
    private static final AgentExecutionContext BOUND_USER_CTX =
            new AgentExecutionContext("user-1", false, "s-user", "plan-sess-1", "tok-123", "trip-1", 3, "重庆", 2, "stop-2", "南山一棵树", false);

    @Test
    void getCurrentTripDraftToolMasksUnboundSessionAndSummarizesDraft() throws Exception {
        PlanningApplicationService planner = mock(PlanningApplicationService.class);
        GetCurrentTripDraftTool tool = new GetCurrentTripDraftTool(planner, MAPPER);
        assertFalse(tool.spec().requiresConfirmation());

        // 未绑定行程时执行层返回明确指引，且不调用规划服务
        AgentToolResult unbound = tool.execute("{}", UNBOUND_GUEST_CTX);
        assertFalse(unbound.ok());
        assertTrue(unbound.jsonPayload().contains("NO_PLANNER_SESSION"));
        verifyNoInteractions(planner);

        // 绑定后返回精简日程摘要，过滤大体积折线字段
        Map<String, Object> trip = Map.of(
                "title", "重庆三日经典游",
                "city", "重庆",
                "version", 3,
                "pinnedStopIds", List.of("stop-1"),
                "days", List.of(
                        Map.of("day", 1, "title", "母城人文", "stops", List.of(
                                Map.of("stopId", "stop-1", "name", "解放碑", "category", "地标", "pinned", true, "polyline", "HUGE_POLYLINE")
                        )),
                        Map.of("day", 2, "title", "山城夜景", "stops", List.of(
                                Map.of("stopId", "stop-2", "name", "南山一棵树", "category", "观景")
                        ))
                )
        );
        when(planner.get("plan-sess-1", "tok-123"))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of("ok", true, "currentVersion", 3, "trip", trip)));

        AgentToolResult res = tool.execute("{\"day\":2}", BOUND_USER_CTX);
        assertTrue(res.ok());
        assertFalse(res.jsonPayload().contains("HUGE_POLYLINE"));
        JsonNode json = MAPPER.readTree(res.jsonPayload());
        assertEquals(3, json.path("currentVersion").asInt());
        assertEquals(1, json.path("days").size());
        assertEquals("南山一棵树", json.path("days").get(0).path("stops").get(0).path("name").asText());
    }

    @Test
    void previewAndConfirmApplyTripAdjustmentToolsDelegateToDeterministicSandbox() throws Exception {
        AgentTripAuthoringService authoringService = mock(AgentTripAuthoringService.class);
        PreviewTripAdjustmentTool previewTool = new PreviewTripAdjustmentTool(authoringService, MAPPER);
        ConfirmApplyTripAdjustmentTool applyTool = new ConfirmApplyTripAdjustmentTool(authoringService, MAPPER);

        assertFalse(previewTool.spec().requiresConfirmation());
        assertTrue(applyTool.spec().requiresConfirmation(), "正式应用调整必须标记 requiresConfirmation=true");

        when(authoringService.previewPatch(eq(BOUND_USER_CTX), any()))
                .thenReturn(new AgentTripAuthoringService.PreviewPatchResult(
                        true, "", "将南山一棵树替换为重庆中国三峡博物馆", "agent-prop-888", 3,
                        List.of(Map.of("action", "ADDED", "stopName", "第2天 · 重庆中国三峡博物馆")),
                        List.of("新增 第2天 · 重庆中国三峡博物馆")
                ));

        AgentToolResult previewRes = previewTool.execute(
                "{\"changeSummary\":\"将南山一棵树替换为三峡博物馆\",\"baseRevision\":3,\"days\":[{\"day\":2,\"stops\":[{\"name\":\"重庆中国三峡博物馆\",\"type\":\"SCENIC\"}]}]}",
                BOUND_USER_CTX
        );
        assertTrue(previewRes.ok());
        JsonNode previewJson = MAPPER.readTree(previewRes.jsonPayload());
        assertEquals("agent-prop-888", previewJson.path("proposalId").asText());
        assertEquals(3, previewJson.path("baseRevision").asInt());

        when(authoringService.applyPreviewedPatch(eq(BOUND_USER_CTX), eq("agent-prop-888"), eq(3)))
                .thenReturn(new AgentTripAuthoringService.ApplyPatchResult(
                        false, "REVISION_CONFLICT", "行程版本已更新，请刷新后重试", "", 0, Map.of(), List.of()));

        AgentToolResult conflictRes = applyTool.execute(
                "{\"proposalId\":\"agent-prop-888\",\"baseRevision\":3,\"changeSummary\":\"替换为三峡博物馆\"}",
                BOUND_USER_CTX
        );
        assertFalse(conflictRes.ok());
        assertTrue(conflictRes.jsonPayload().contains("REVISION_CONFLICT"));

        when(authoringService.applyPreviewedPatch(eq(BOUND_USER_CTX), eq("agent-prop-888"), eq(3)))
                .thenReturn(new AgentTripAuthoringService.ApplyPatchResult(
                        true, "", "行程调整已成功应用", "plan-sess-1", 4,
                        Map.of("version", 4), List.of("第2天下午")));

        AgentToolResult applyOk = applyTool.execute(
                "{\"proposalId\":\"agent-prop-888\",\"baseRevision\":3,\"changeSummary\":\"替换为三峡博物馆\"}",
                BOUND_USER_CTX
        );
        assertTrue(applyOk.ok());
        JsonNode applyJson = MAPPER.readTree(applyOk.jsonPayload());
        assertEquals(4, applyJson.path("currentVersion").asInt());
        assertTrue(applyJson.path("applied").asBoolean());
    }

    @Test
    void preferenceToolsEnforceAuthAndApplyStructuredPatch() throws Exception {
        PreferencesApplicationService prefService = mock(PreferencesApplicationService.class);
        GetUserPreferencesTool getTool = new GetUserPreferencesTool(prefService, MAPPER);
        ConfirmUpdateUserPreferencesTool updateTool = new ConfirmUpdateUserPreferencesTool(prefService, MAPPER);

        assertFalse(getTool.spec().requiresConfirmation());
        assertTrue(updateTool.spec().requiresConfirmation(), "个人偏好落库必须标记 requiresConfirmation=true");

        // 游客态拒绝查询与修改
        assertFalse(getTool.execute("{}", UNBOUND_GUEST_CTX).ok());
        assertTrue(updateTool.execute("{\"stayArea\":\"解放碑\",\"changeSummary\":\"设置住宿片区\"}", UNBOUND_GUEST_CTX)
                .jsonPayload().contains("LOGIN_REQUIRED"));
        verifyNoInteractions(prefService);

        UserPreferences current = new UserPreferences(
                "user-1", 1, 2L, List.of("历史文化"),
                UserPreferences.WalkingTolerance.LOW,
                BudgetPreference.unspecified(),
                UserPreferences.CompanionPreference.PARENTS,
                UserPreferences.TransportPreference.PUBLIC_TRANSIT,
                UserPreferences.DietPreference.NONE,
                "解放碑", Map.of(), 1000L, 1000L
        );
        when(prefService.getPreferences("user-1")).thenReturn(current);
        AgentToolResult getOk = getTool.execute("{}", BOUND_USER_CTX);
        assertTrue(getOk.ok());
        JsonNode getJson = MAPPER.readTree(getOk.jsonPayload());
        assertEquals(2L, getJson.path("revision").asLong());
        assertEquals("LOW", getJson.path("walkingTolerance").asText());

        // 版本冲突时返回 REVISION_CONFLICT
        when(prefService.mergePreferences(eq("user-1"), any(), eq(1L)))
                .thenThrow(new PreferenceConflictException(1L, 2L));
        AgentToolResult conflict = updateTool.execute(
                "{\"walkingTolerance\":\"LOW\",\"expectedRevision\":1,\"changeSummary\":\"降低步行强度\"}",
                BOUND_USER_CTX
        );
        assertFalse(conflict.ok());
        assertTrue(conflict.jsonPayload().contains("REVISION_CONFLICT"));

        // 正常合并更新
        UserPreferences updated = new UserPreferences(
                "user-1", 1, 3L, List.of("历史文化"),
                UserPreferences.WalkingTolerance.LOW,
                BudgetPreference.unspecified(),
                UserPreferences.CompanionPreference.PARENTS,
                UserPreferences.TransportPreference.TAXI,
                UserPreferences.DietPreference.NONE,
                "南滨路", Map.of(), 1000L, 2000L
        );
        when(prefService.mergePreferences(eq("user-1"), any(), eq(2L)))
                .thenReturn(new PreferencesService.MutationResult(updated, false));
        AgentToolResult updateOk = updateTool.execute(
                "{\"transportPreference\":\"TAXI\",\"stayArea\":\"南滨路\",\"expectedRevision\":2,\"changeSummary\":\"改为打车偏好并入住南滨路\"}",
                BOUND_USER_CTX
        );
        assertTrue(updateOk.ok());
        assertEquals(3L, MAPPER.readTree(updateOk.jsonPayload()).path("revision").asLong());
    }
}
