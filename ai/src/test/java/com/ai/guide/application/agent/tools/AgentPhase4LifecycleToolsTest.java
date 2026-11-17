package com.ai.guide.application.agent.tools;

import com.ai.guide.application.planner.PlanningActor;
import com.ai.guide.application.planner.PlanningApplicationService;
import com.ai.guide.application.trip.TripApplicationService;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.planner.model.PlanRequest;
import com.ai.guide.domain.planner.model.PlannerPinsRequest;
import com.ai.guide.domain.planner.model.PlannerSaveResult;
import com.ai.guide.domain.planner.service.PlannerService;
import com.ai.guide.domain.planner.service.TravelConstraintParser;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.service.TripService;
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
 * Phase 4 行程全生命周期扩展工具单元测试：
 * 覆盖 {@link CreateTripPlanTool}、{@link ConfirmUpdatePinnedStopsTool} 与 {@link ConfirmSaveFormalTripTool}。
 */
class AgentPhase4LifecycleToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AgentExecutionContext GUEST_CTX =
            new AgentExecutionContext("", true, "guest-s1", "", "", "", null, "重庆", null, "", "", false);
    private static final AgentExecutionContext USER_BOUND_CTX =
            new AgentExecutionContext("u-1", false, "s-1", "ps-9", "tok-9", "trip-9", 2, "重庆", 1, "", "", false);

    @Test
    void createTripPlanToolEnforcesLoginExcludesBulkPolylineFromLlmAndAttachesClientData() throws Exception {
        PlanningApplicationService planner = mock(PlanningApplicationService.class);
        CreateTripPlanTool tool = new CreateTripPlanTool(planner, MAPPER);
        assertFalse(tool.spec().requiresConfirmation());

        // 游客态拒绝对话创建行程
        AgentToolResult guestRes = tool.execute("{\"prompt\":\"重庆两日游\"}", GUEST_CTX);
        assertFalse(guestRes.ok());
        assertTrue(guestRes.jsonPayload().contains("LOGIN_REQUIRED"));
        verifyNoInteractions(planner);

        Map<String, Object> generatedTrip = Map.of(
                "title", "重庆两日慢游",
                "city", "重庆",
                "version", 1,
                "days", List.of(
                        Map.of("day", 1, "stops", List.of(
                                Map.of("stopId", "stop-jfb", "name", "解放碑", "polyline", "GIANT_POLYLINE_ARRAY")
                        ))
                )
        );
        when(planner.create(any(PlanRequest.class))).thenReturn(new PlannerService.ServiceResult(200, Map.of(
                "ok", true,
                "sessionId", "ps-new-100",
                "currentVersion", 1,
                "trip", generatedTrip
        )));

        AgentToolResult res = tool.execute(
                "{\"prompt\":\"周末两天带父母玩重庆，少走路\",\"durationDays\":2,\"companions\":\"带父母\",\"pace\":\"少走路\"}",
                USER_BOUND_CTX
        );
        assertTrue(res.ok());
        assertFalse(res.jsonPayload().contains("GIANT_POLYLINE_ARRAY"), "进入 LLM 上下文的摘要不得夹带海量折线");
        JsonNode llmJson = MAPPER.readTree(res.jsonPayload());
        assertEquals("ps-new-100", llmJson.path("plannerSessionId").asText());
        assertEquals(1, llmJson.path("days").size());
        assertEquals("ps-new-100", res.clientData().get("plannerSessionId"));
        assertEquals(generatedTrip, res.clientData().get("createdTrip"));

        ArgumentCaptor<PlanRequest> reqCaptor = ArgumentCaptor.forClass(PlanRequest.class);
        verify(planner).create(reqCaptor.capture());
        assertEquals(2, reqCaptor.getValue().getConstraints().get("durationDays"));
        assertEquals("带父母", reqCaptor.getValue().getConstraints().get("companions"));
    }

    @Test
    void confirmUpdatePinnedStopsToolRequiresHilAndEnforcesCasVersion() throws Exception {
        PlanningApplicationService planner = mock(PlanningApplicationService.class);
        ConfirmUpdatePinnedStopsTool tool = new ConfirmUpdatePinnedStopsTool(planner, MAPPER);
        assertTrue(tool.spec().requiresConfirmation());

        when(planner.updatePins(any(PlanningActor.class), eq("ps-9"), any(PlannerPinsRequest.class)))
                .thenReturn(new PlannerService.ServiceResult(409, Map.of("ok", false, "message", "版本冲突")));
        AgentToolResult conflict = tool.execute(
                "{\"addStopIds\":[\"stop-jfb\"],\"expectedVersion\":2,\"changeSummary\":\"固定解放碑\"}",
                USER_BOUND_CTX
        );
        assertFalse(conflict.ok());
        assertTrue(conflict.jsonPayload().contains("REVISION_CONFLICT"));

        when(planner.updatePins(any(PlanningActor.class), eq("ps-9"), any(PlannerPinsRequest.class)))
                .thenReturn(new PlannerService.ServiceResult(200, Map.of(
                        "ok", true,
                        "sessionId", "ps-9",
                        "currentVersion", 3,
                        "pinnedStopIds", List.of("stop-jfb"),
                        "trip", Map.of("version", 3)
                )));
        AgentToolResult ok = tool.execute(
                "{\"addStopIds\":[\"stop-jfb\"],\"expectedVersion\":2,\"changeSummary\":\"固定解放碑\"}",
                USER_BOUND_CTX
        );
        assertTrue(ok.ok());
        JsonNode okJson = MAPPER.readTree(ok.jsonPayload());
        assertEquals(3, okJson.path("currentVersion").asInt());
        assertEquals("stop-jfb", okJson.path("pinnedStopIds").get(0).asText());
    }

    @Test
    void confirmSaveFormalTripToolRequiresHilAndAuthenticatedBoundSession() throws Exception {
        PlanningApplicationService planner = mock(PlanningApplicationService.class);
        ConfirmSaveFormalTripTool tool = new ConfirmSaveFormalTripTool(planner, MAPPER);
        assertTrue(tool.spec().requiresConfirmation());

        AgentToolResult guestSave = tool.execute("{\"changeSummary\":\"保存行程\"}", GUEST_CTX);
        assertFalse(guestSave.ok());
        assertTrue(guestSave.jsonPayload().contains("LOGIN_REQUIRED"));

        when(planner.save(new PlanningActor("u-1", false), "ps-9", "tok-9"))
                .thenReturn(new PlannerSaveResult(200, Map.of(
                        "ok", true,
                        "tripId", "formal-trip-88",
                        "currentVersion", 2,
                        "trip", Map.of("id", "formal-trip-88", "currentVersion", 2)
                )));
        AgentToolResult ok = tool.execute("{\"changeSummary\":\"将当前重庆两日游保存为正式行程\"}", USER_BOUND_CTX);
        assertTrue(ok.ok());
        JsonNode json = MAPPER.readTree(ok.jsonPayload());
        assertTrue(json.path("saved").asBoolean());
        assertEquals("formal-trip-88", json.path("tripId").asText());
    }

    @Test
    void confirmSaveFormalTripToolAppliesCustomTitleAndParserIgnoresDayOrdinals() throws Exception {
        TravelConstraintParser parser = new TravelConstraintParser();
        var parsed = parser.parse("帮我规划一个2天重庆轻松亲子游，第1天洪崖洞和解放碑，第2天重庆动物园");
        assertEquals(2, parsed.getDurationDays(), "第1天序数词不得误判覆盖2天行程总天数");

        PlanningApplicationService planner = mock(PlanningApplicationService.class);
        TripApplicationService tripApp = mock(TripApplicationService.class);
        ConfirmSaveFormalTripTool tool = new ConfirmSaveFormalTripTool(planner, MAPPER, tripApp);

        Trip initialTrip = new Trip("formal-trip-99", "u-1", "重庆2天·专属定制方案", "ACTIVE", 1, 1000L, 1000L, Map.of());
        Trip renamedTrip = new Trip("formal-trip-99", "u-1", "山城调试验证之旅", "ACTIVE", 2, 1000L, 2000L, Map.of("title", "山城调试验证之旅"));
        when(planner.save(new PlanningActor("u-1", false), "ps-9", "tok-9"))
                .thenReturn(new PlannerSaveResult(200, Map.of(
                        "ok", true,
                        "tripId", "formal-trip-99",
                        "currentVersion", 1,
                        "trip", initialTrip
                )));
        when(tripApp.updateTrip(eq("u-1"), eq("formal-trip-99"), any()))
                .thenReturn(TripService.OperationResult.success(200, "行程已更新", "UPDATED", renamedTrip));

        AgentToolResult renamed = tool.execute(
                "{\"title\":\"山城调试验证之旅\",\"changeSummary\":\"保存为山城调试验证之旅\"}",
                USER_BOUND_CTX
        );
        assertTrue(renamed.ok());
        JsonNode renamedJson = MAPPER.readTree(renamed.jsonPayload());
        assertEquals("山城调试验证之旅", renamedJson.path("title").asText());
        assertEquals(2, renamedJson.path("currentVersion").asInt());
    }
}
