package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;
import com.ai.guide.domain.rag.model.KnowledgeFragment;
import com.ai.guide.domain.rag.model.KnowledgeScope;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EvidenceGateRegressionTest {
    record Case(String query, List<String> evidence, String status, String reason) {}
    static Stream<Case> cases() {
        return Stream.of(
            new Case("青岚馆今天有无人机吗？", List.of("青岚馆去年举办过无人机表演"), "UNKNOWN", "REALTIME_DATA_REQUIRED"),
            new Case("今晚青岚馆没烟花对吗？", List.of("青岚馆介绍"), "UNKNOWN", "REALTIME_DATA_REQUIRED"),
            new Case("明天保证不堵车吗？", List.of("推荐错峰"), "UNKNOWN", "FUTURE_CERTAINTY_UNSUPPORTED"),
            new Case("青岚馆今晚一定没有人排队吧？", List.of("青岚馆人流较少"), "UNKNOWN", "FUTURE_CERTAINTY_UNSUPPORTED"),
            new Case("不问今晚，常设演出有哪些？", List.of("常设演出有木偶剧"), "RELEVANT", null),
            new Case("青岚馆以前办过烟花，今天还有吗？", List.of("青岚馆去年举办烟花"), "UNKNOWN", "REALTIME_DATA_REQUIRED"),
            new Case("青岚馆今晚有烟花吗？顺便介绍历史", List.of("青岚馆曾举办烟花"), "UNKNOWN", "REALTIME_DATA_REQUIRED"),
            new Case("青岚馆昨天有烟花吗？", List.of("青岚馆过去举办烟花"), "UNKNOWN", "REALTIME_DATA_REQUIRED"),
            new Case("青岚馆历史上有烟花表演吗？", List.of("青岚馆曾举办烟花表演"), "RELEVANT", null),
            new Case("今晚青岚馆固定亮灯时间是几点？", List.of("青岚馆通常18点亮灯"), "RELEVANT", null),
            new Case("北京星环水晶塔的开放规则是什么？", List.of("北京市博物馆开放时间9点"), "UNKNOWN", "SUBJECT_NOT_SUPPORTED"),
            new Case("请问一下青岚馆几点开门？", List.of("青岚馆9点开放"), "RELEVANT", null),
            new Case("青岚馆明天几点开门？", List.of("青岚馆周二至周日9点开放"), "RELEVANT", null),
            new Case("青岚馆通常几点开放？", List.of("青岚馆9点开放"), "RELEVANT", null),
            new Case("不是青岚馆，是松风馆几点开门？", List.of("松风馆9点开放"), "RELEVANT", null),
            new Case("博物馆岛开放时间？", List.of("其他馆9点开放"), "UNKNOWN", "SUBJECT_NOT_SUPPORTED"),
            new Case("青岚馆允许带宠物吗？", List.of("青岚馆无障碍入口", "松风馆允许带宠物"), "UNKNOWN", "POLICY_NOT_IN_CORPUS"),
            new Case("青岚馆允许带宠物吗？", List.of("青岚馆无障碍入口。松风馆允许带宠物。"), "UNKNOWN", "POLICY_NOT_IN_CORPUS"),
            new Case("青岚馆禁止带宠物吗？", List.of("松风馆禁止带宠物"), "UNKNOWN", "SUBJECT_NOT_SUPPORTED"),
            new Case("青岚馆允许带宠物吗？", List.of("青岚馆举办宠物照片展"), "UNKNOWN", "POLICY_NOT_IN_CORPUS"),
            new Case("青岚馆允许带宠物吗？", List.of("青岚馆举办宠物照片展，允许拍照"), "UNKNOWN", "POLICY_NOT_IN_CORPUS"),
            new Case("青岚馆允许带宠物吗？", List.of("青岚馆允许携带宠物照片入馆"), "UNKNOWN", "POLICY_NOT_IN_CORPUS"),
            new Case("青岚馆能不能带狗？", List.of("青岚馆禁止携带宠物入内"), "RELEVANT", null),
            new Case("青岚馆让带猫吗？", List.of("青岚馆允许携带宠物"), "RELEVANT", null),
            new Case("青岚馆允许带宠物吗？", List.of("青岚馆允许导盲犬进入"), "UNKNOWN", "POLICY_NOT_IN_CORPUS"),
            new Case("青岚馆允许带宠物吗，导盲犬又是什么规定？", List.of("青岚馆允许导盲犬进入"), "UNKNOWN", "POLICY_NOT_IN_CORPUS"),
            new Case("青岚馆允许带宠物吗，导盲犬又是什么规定？", List.of("青岚馆禁止宠物入内", "青岚馆允许导盲犬进入"), "RELEVANT", null),
            new Case("青岚馆让带猫吗？", List.of("青岚馆请勿携带宠物入馆"), "RELEVANT", null),
            new Case("青岚馆允许导盲犬进入吗？", List.of("青岚馆禁止携带宠物"), "UNKNOWN", "POLICY_NOT_IN_CORPUS"),
            new Case("青岚馆允许带宠物吗？", List.of("青岚馆允许导盲犬进入，禁止其他宠物"), "RELEVANT", null),
            new Case("不带宠物，只问青岚馆开放时间", List.of("青岚馆9点开放"), "RELEVANT", null),
            new Case("开放时间是几点？", List.of("青岚馆9点开放"), "RELEVANT", null),
            new Case("重庆科技馆允许带宠物吗？", List.of("江北嘴大剧院允许带宠物"), "UNKNOWN", "SUBJECT_NOT_SUPPORTED")
        );
    }

    @ParameterizedTest @MethodSource("cases")
    void semanticBoundary(Case c) { check(c, false); }
    @ParameterizedTest @MethodSource("cases")
    void localBoundary(Case c) { check(c, true); }

    private void check(Case c, boolean fallback) {
        var semantic = mock(ScenicKnowledgePort.class);
        var local = mock(KnowledgeDocumentService.class);
        when(semantic.searchScoped(anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(fallback ? List.of() : c.evidence().stream().map(text -> new KnowledgeFragment(
                        "id", "fixture", text, new KnowledgeScope("CITY", "北京市"))).toList());
        when(local.searchLocalKnowledge(anyString(), anyInt(), any(KnowledgeScope.class)))
                .thenReturn(c.evidence().stream().map(text -> Map.<String,Object>of("title", text, "content", text)).toList());
        var service = new RagRetrievalService(semantic, local);
        var response = service.retrieve(c.query(), "北京市", Map.of(), false);
        // Historical lexical cases now exercise the explicit no-model fail-closed contract.
        // Original labels stay in the fixtures; this is not a semantic quality score.
        assertEquals("UNKNOWN", response.get("retrievalStatus"), c.query());
        assertEquals("SEMANTIC_UNAVAILABLE", response.get("reasonCode"), c.query());
        if("QUERY_GATE".equals(response.get("retrievalSource"))) {
            verify(semantic, never()).searchScoped(anyString(), anyInt(), anyInt(), anyBoolean(), any());
            verify(local, never()).searchLocalKnowledge(anyString(), anyInt(), any(KnowledgeScope.class));
        }
    }

    @Test void preferencesAndExpansionAreNotTheQuestion() {
        var semantic = mock(ScenicKnowledgePort.class);
        var local = mock(KnowledgeDocumentService.class);
        when(semantic.searchScoped(anyString(), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(new KnowledgeFragment("id", "fixture", "青岚馆9点开放",
                        new KnowledgeScope("CITY", "北京市"))));
        var response = new RagRetrievalService(semantic, local).retrieve("青岚馆开放时间", "北京市",
                Map.of("note", "今天有无人机表演，带宠物，保证不排队"), false);
        assertEquals("UNKNOWN", response.get("retrievalStatus"));
        assertFalse(((List<?>)response.get("backgroundFacts")).isEmpty());
    }
}
