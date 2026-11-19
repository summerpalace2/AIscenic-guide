package com.ai.guide.application.agent;

import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoCoordinate;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiCandidate;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.CreateResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.MutationResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.gateway.RouteGatewayPort;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.HydrationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 全智能体原生编排与确定性领域校核测试：
 * 验证严格按天保留用户指定站点、高德实景 POI Grounding Gate、Pin 站点不可破坏护栏与 CAS 版本控制。
 */
class AgentTripAuthoringServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void createDraftHonorsDayAssignmentsGroundsLivePoisAndRejectsUnverifiedPlaces() throws Exception {
        PlannerSessionRepositoryPort repo = mock(PlannerSessionRepositoryPort.class);
        RouteGatewayPort routeGateway = mock(RouteGatewayPort.class);
        GeoRouteGatewayPort geoGateway = mock(GeoRouteGatewayPort.class);

        when(geoGateway.isConfigured()).thenReturn(true);
        when(geoGateway.searchPoi("洪崖洞", "重庆")).thenReturn(new GeoPoiOutcome(
                GeoOutcomeStatus.SUCCESS,
                List.of(new GeoPoiCandidate("poi-hyd", "洪崖洞", new GeoCoordinate(106.579, 29.562),
                        "渝中区嘉陵江滨江路88号", "风景名胜", null, null, null, null, null)),
                "ok", Instant.now()
        ));
        when(geoGateway.searchPoi("礼嘉智慧公园", "重庆")).thenReturn(new GeoPoiOutcome(
                GeoOutcomeStatus.SUCCESS,
                List.of(new GeoPoiCandidate("poi-lj", "礼嘉智慧公园", new GeoCoordinate(106.489, 29.687),
                        "渝北区礼嘉街道", "风景名胜", null, null, null, null, null)),
                "ok", Instant.now()
        ));
        when(geoGateway.searchPoi("不存在的幻觉景点X", "重庆")).thenReturn(new GeoPoiOutcome(
                GeoOutcomeStatus.UNAVAILABLE, List.of(), "未命中", Instant.now()
        ));
        when(routeGateway.hydrate(any())).thenReturn(new HydrationResult("高德实时接口", 2, 1, 2, false));
        when(repo.create(eq("USER"), eq("user-1"), anyString(), any(), any(), any(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    Map<String, Object> savedTrip = inv.getArgument(5);
                    StoredSession session = new StoredSession(
                            "session-agent-1", "USER", "user-1", "hash", "fp", "prompt",
                            Map.of(), AppliedPreferencesSnapshot.empty(), savedTrip,
                            1, 1, "ACTIVE", 1000L, 1000L
                    );
                    return CreateResult.created(session, "");
                });

        AgentTripAuthoringService service = new AgentTripAuthoringService(
                repo, routeGateway, geoGateway, null, null, null, null, MAPPER);
        AgentExecutionContext ctx = new AgentExecutionContext(
                "user-1", false, "s-1", "", "", "", null, "重庆", null, "", "", true);

        // 1. 幻觉景点无坐标且高德查不到时，必须拒收并提醒 Agent 重新检索
        JsonNode hallucinated = MAPPER.readTree("""
                {"title":"测试","days":[{"day":1,"theme":"首日","stops":[{"name":"不存在的幻觉景点X","type":"SCENIC"}]}]}
                """);
        var badRes = service.createDraft(ctx, hallucinated);
        assertFalse(badRes.ok());
        assertEquals("UNVERIFIED_STOP_LOCATION", badRes.errorCode());
        assertTrue(badRes.unverifiedStops().contains("不存在的幻觉景点X"));
        verify(repo, never()).create(any(), any(), any(), any(), any(), any(), any(), any());

        // 2. 合法编排：第1天洪崖洞，第2天礼嘉智慧公园（非静态41景目录长尾点），必须严格保留各自所在天
        JsonNode validPlan = MAPPER.readTree("""
                {
                  "title": "重庆双日定制",
                  "days": [
                    {"day": 1, "theme": "江畔夜景", "stops": [{"name": "洪崖洞", "type": "SCENIC", "time": "18:30"}]},
                    {"day": 2, "theme": "亲子科技", "stops": [
                      {"name": "礼嘉智慧公园", "type": "SCENIC", "time": "10:00"},
                      {"name": "片区自由漫步", "type": "FREE_TIME", "time": "15:00"}
                    ]}
                  ]
                }
                """);
        var okRes = service.createDraft(ctx, validPlan);
        assertTrue(okRes.ok());
        assertEquals("session-agent-1", okRes.sessionId());
        assertEquals("AGENT_NATIVE", okRes.trip().get("planMode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) okRes.trip().get("days");
        assertEquals(2, days.size());
        assertEquals("洪崖洞", ((List<Map<String, Object>>) days.get(0).get("stops")).get(0).get("name"));
        assertEquals("礼嘉智慧公园", ((List<Map<String, Object>>) days.get(1).get("stops")).get(0).get("name"));
        assertEquals("106.489,29.687", ((List<Map<String, Object>>) days.get(1).get("stops")).get(0).get("location"));
        verify(routeGateway).hydrate(any());
    }

    @Test
    void previewPatchProtectsPinnedStopsAndApplyEnforcesCasRevision() throws Exception {
        PlannerSessionRepositoryPort repo = mock(PlannerSessionRepositoryPort.class);
        RouteGatewayPort routeGateway = mock(RouteGatewayPort.class);
        when(routeGateway.hydrate(any())).thenReturn(new HydrationResult("高德实时接口", 1, 1, 1, false));

        Map<String, Object> beforeTrip = Map.of(
                "title", "山城两日游",
                "city", "重庆",
                "version", 2,
                "pinnedStopIds", List.of("stop-hyd"),
                "days", List.of(
                        Map.of("day", 1, "theme", "夜色", "stops", List.of(
                                Map.of("id", "stop-hyd", "stopId", "stop-hyd", "venueId", "amap-poi-hyd",
                                        "name", "洪崖洞", "type", "SCENIC", "location", "106.579,29.562")
                        )),
                        Map.of("day", 2, "theme", "古镇", "stops", List.of(
                                Map.of("id", "stop-cqk", "stopId", "stop-cqk", "venueId", "amap-poi-cqk",
                                        "name", "磁器口古镇", "type", "SCENIC", "location", "106.453,29.579")
                        ))
                )
        );
        StoredSession currentSession = new StoredSession(
                "ps-9", "USER", "user-1", "h", "f", "p", Map.of(),
                AppliedPreferencesSnapshot.empty(), beforeTrip, 2, 2, "ACTIVE", 1000L, 1000L
        );
        when(repo.find("ps-9", "USER", "user-1", "")).thenReturn(currentSession);

        AgentTripAuthoringService service = new AgentTripAuthoringService(
                repo, routeGateway, null, null, null, null, null, MAPPER);
        AgentExecutionContext ctx = new AgentExecutionContext(
                "user-1", false, "s-1", "ps-9", "", "", 2, "重庆", null, "", "", true);

        // 1. 尝试删掉第1天已固定的洪崖洞，必须被 PINNED_STOP_PROTECTED 拦截
        JsonNode breakPin = MAPPER.readTree("""
                {
                  "changeSummary": "换成解放碑",
                  "baseRevision": 2,
                  "days": [
                    {"day": 1, "stops": [{"name": "解放碑", "type": "SCENIC", "location": "106.577,29.557"}]},
                    {"day": 2, "stops": [{"stopId": "stop-cqk", "name": "磁器口古镇", "type": "SCENIC"}]}
                  ]
                }
                """);
        var blocked = service.previewPatch(ctx, breakPin);
        assertFalse(blocked.ok());
        assertEquals("PINNED_STOP_PROTECTED", blocked.errorCode());

        // 2. 保留第1天固定洪崖洞，把第2天磁器口替换为重庆科技馆 -> 预演成功并通过 HIL 确认落库
        JsonNode validPatch = MAPPER.readTree("""
                {
                  "changeSummary": "保留第1天洪崖洞，第2天改去重庆科技馆",
                  "baseRevision": 2,
                  "days": [
                    {"day": 1, "stops": [{"stopId": "stop-hyd", "name": "洪崖洞", "type": "SCENIC"}]},
                    {"day": 2, "stops": [{"name": "重庆科技馆", "type": "SCENIC", "location": "106.582,29.566"}]}
                  ]
                }
                """);
        var previewed = service.previewPatch(ctx, validPatch);
        assertTrue(previewed.ok(), previewed.message());
        assertFalse(previewed.stopChanges().isEmpty());

        // 确认前校验 CAS：若错误基线版本 1 必须冲突
        var staleApply = service.applyPreviewedPatch(ctx, previewed.proposalId(), 1);
        assertFalse(staleApply.ok());
        assertEquals("REVISION_CONFLICT", staleApply.errorCode());

        when(repo.update(eq(currentSession), any(), eq(2), eq("AGENT_NATIVE_PATCH"), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    Map<String, Object> newTrip = inv.getArgument(1);
                    StoredSession next = new StoredSession(
                            "ps-9", "USER", "user-1", "h", "f", "p", Map.of(),
                            AppliedPreferencesSnapshot.empty(), newTrip, 3, 3, "ACTIVE", 1000L, 2000L
                    );
                    return MutationResult.updated(next);
                });
        var applied = service.applyPreviewedPatch(ctx, previewed.proposalId(), 2);
        assertTrue(applied.ok());
        assertEquals(3, applied.currentVersion());
    }

    @Test
    void previewSurgicalOperationsReplacesTargetStopWithoutTouchingOtherDays() throws Exception {
        PlannerSessionRepositoryPort repo = mock(PlannerSessionRepositoryPort.class);
        RouteGatewayPort routeGateway = mock(RouteGatewayPort.class);
        when(routeGateway.hydrate(any())).thenReturn(new HydrationResult("高德实时接口", 1, 1, 1, false));

        Map<String, Object> beforeTrip = Map.of(
                "title", "山城双日漫游",
                "city", "重庆",
                "version", 1,
                "pinnedStopIds", List.of("stop-hyd"),
                "days", List.of(
                        Map.of("day", 1, "theme", "母城记忆", "stops", List.of(
                                Map.of("id", "stop-hyd", "stopId", "stop-hyd", "venueId", "amap-poi-hyd",
                                        "name", "洪崖洞", "type", "SCENIC", "location", "106.579,29.562"),
                                Map.of("id", "stop-sbt", "stopId", "stop-sbt", "venueId", "amap-poi-sbt",
                                        "name", "十八梯传统风貌区", "type", "SCENIC", "time", "14:00", "location", "106.577,29.553")
                        )),
                        Map.of("day", 2, "theme", "动物园古镇", "stops", List.of(
                                Map.of("id", "stop-zoo", "stopId", "stop-zoo", "venueId", "amap-poi-zoo",
                                        "name", "重庆动物园", "type", "SCENIC", "location", "106.505,29.504")
                        ))
                )
        );
        StoredSession currentSession = new StoredSession(
                "ps-surgical", "USER", "user-1", "h", "f", "p", Map.of(),
                AppliedPreferencesSnapshot.empty(), beforeTrip, 1, 1, "ACTIVE", 1000L, 1000L
        );
        when(repo.find("ps-surgical", "USER", "user-1", "")).thenReturn(currentSession);

        AgentTripAuthoringService service = new AgentTripAuthoringService(
                repo, routeGateway, null, null, null, null, null, MAPPER);
        AgentExecutionContext ctx = new AgentExecutionContext(
                "user-1", false, "s-1", "ps-surgical", "", "", 1, "重庆", 1, "", "", true);

        // 1. 尝试直接用 operations 替换第1天已固定的洪崖洞 -> 拦截 PINNED_STOP_PROTECTED
        JsonNode pinBlockedPatch = MAPPER.readTree("""
                {
                  "changeSummary": "换掉洪崖洞",
                  "baseRevision": 1,
                  "operations": [
                    {
                      "op": "REPLACE_STOP",
                      "day": 1,
                      "targetStopName": "洪崖洞",
                      "newStop": {"name": "湖广会馆", "type": "SCENIC", "location": "106.584,29.557"}
                    }
                  ]
                }
                """);
        var blocked = service.previewPatch(ctx, pinBlockedPatch);
        assertFalse(blocked.ok());
        assertEquals("PINNED_STOP_PROTECTED", blocked.errorCode());

        // 2. 用 operations 仅把第1天十八梯替换为湖广会馆 -> 继承原时段 14:00、保留第1天洪崖洞与第2天动物园不动
        JsonNode surgicalPatch = MAPPER.readTree("""
                {
                  "changeSummary": "将第1天十八梯替换为湖广会馆",
                  "baseRevision": 1,
                  "operations": [
                    {
                      "op": "REPLACE_STOP",
                      "day": 1,
                      "targetStopName": "十八梯",
                      "newStop": {"name": "重庆湖广会馆", "type": "SCENIC", "district": "渝中区", "location": "106.584982,29.557342"}
                    }
                  ]
                }
                """);
        var previewed = service.previewPatch(ctx, surgicalPatch);
        assertTrue(previewed.ok(), previewed.message());
        assertEquals(1, previewed.stopChanges().size());
        Map<String, Object> change = previewed.stopChanges().get(0);
        assertEquals("UPDATED", change.get("action"));
        assertFalse(String.valueOf(change.get("key")).isBlank(), "必须返回真实 stop.id 作为定位 key");
        @SuppressWarnings("unchecked")
        Map<String, Object> changeFields = (Map<String, Object>) change.get("fields");
        @SuppressWarnings("unchecked")
        Map<String, Object> nameDiff = (Map<String, Object>) changeFields.get("name");
        assertEquals("十八梯传统风貌区", nameDiff.get("before"));
        assertEquals("重庆湖广会馆", nameDiff.get("after"));

        when(repo.update(eq(currentSession), any(), eq(1), eq("AGENT_NATIVE_PATCH"), anyString(), anyString(), any()))
                .thenAnswer(inv -> {
                    Map<String, Object> newTrip = inv.getArgument(1);
                    return MutationResult.updated(new StoredSession(
                            "ps-surgical", "USER", "user-1", "h", "f", "p", Map.of(),
                            AppliedPreferencesSnapshot.empty(), newTrip, 2, 2, "ACTIVE", 1000L, 2000L
                    ));
                });
        var applied = service.applyPreviewedPatch(ctx, previewed.proposalId(), 1);
        assertTrue(applied.ok(), applied.message());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) applied.trip().get("days");
        assertEquals(2, days.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day1Stops = (List<Map<String, Object>>) days.get(0).get("stops");
        assertEquals(2, day1Stops.size());
        assertEquals("洪崖洞", day1Stops.get(0).get("name"));
        assertEquals("重庆湖广会馆", day1Stops.get(1).get("name"));
        assertEquals("14:00", day1Stops.get(1).get("time"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> day2Stops = (List<Map<String, Object>>) days.get(1).get("stops");
        assertEquals(1, day2Stops.size());
        assertEquals("重庆动物园", day2Stops.get(0).get("name"));
    }
}
