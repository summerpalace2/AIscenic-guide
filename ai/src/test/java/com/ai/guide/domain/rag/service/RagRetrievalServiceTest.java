package com.ai.guide.domain.rag.service;




import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagRetrievalServiceTest {

    @Test
    void semanticResultsUseJavaStructuredBoundary() {
        ScenicDataImportService semantic = mock(ScenicDataImportService.class);
        KnowledgeDocumentService local = mock(KnowledgeDocumentService.class);
        when(semantic.searchFragments(anyString(), eq(25), eq(12)))
                .thenReturn(List.of("【洪崖洞】\n优先从平街入口进入。"));

        RagRetrievalService service = new RagRetrievalService(semantic, local);
        Map<String, Object> result = service.retrieve("重庆", "重庆", Map.of(), false);

        assertEquals(true, result.get("ok"));
        assertEquals("Java Qdrant 语义检索", result.get("mode"));
        assertEquals("Java Core Backend / Qdrant", result.get("source"));
        assertEquals(false, ((Map<?, ?>) result.get("vectorStore")).get("directFromNode"));
        assertFalse(((List<?>) result.get("facts")).isEmpty());
        verify(local, org.mockito.Mockito.never()).searchLocalKnowledge(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyInt());
    }

    @Test
    void qdrantProviderFailureUsesJavaKnowledgeDatabaseFallbackWithoutEscaping() {
        ScenicDataImportService semantic = mock(ScenicDataImportService.class);
        KnowledgeDocumentService local = mock(KnowledgeDocumentService.class);
        when(semantic.searchFragments(anyString(), eq(25), eq(12)))
                .thenThrow(new IllegalStateException("Qdrant unavailable"));
        when(local.searchLocalKnowledge(anyString(), eq(5))).thenReturn(List.of(
                Map.of("title", "降级知识", "content", "Qdrant 不可用时仍返回本地核验内容")
        ));

        RagRetrievalService service = new RagRetrievalService(semantic, local);
        Map<String, Object> result = service.retrieve("重庆", "重庆", Map.of(), false);

        assertEquals(true, result.get("ok"));
        assertEquals("Java 本地知识回退（关键词检索）", result.get("mode"));
        assertTrue(String.valueOf(result.get("reason")).contains("降级"));
        assertEquals("Java kb_document", result.get("source"));
    }

    @Test
    void qdrantExceptionTriggersLocalKnowledgeFallbackWithRealDocumentStructure() {
        ScenicDataImportService semantic = mock(ScenicDataImportService.class);
        KnowledgeDocumentService local = mock(KnowledgeDocumentService.class);
        when(semantic.searchFragments(anyString(), eq(25), eq(12)))
                .thenThrow(new RuntimeException("Connection refused: /127.0.0.1:6334"));
        when(local.searchLocalKnowledge(anyString(), eq(5))).thenReturn(List.of(
                Map.of("title", "洪崖洞夜景", "content", "洪崖洞依山而建，夜景亮灯时间为18:30-23:00。")
        ));

        RagRetrievalService service = new RagRetrievalService(semantic, local);
        Map<String, Object> result = service.retrieve("洪崖洞夜景门票", "重庆", Map.of(), false);

        assertEquals(true, result.get("ok"));
        assertEquals("Java 本地知识回退（关键词检索）", result.get("mode"));
        assertEquals("Java kb_document", result.get("source"));
        assertTrue(String.valueOf(result.get("reason")).contains("降级"));
        assertFalse(((List<?>) result.get("facts")).isEmpty());
    }

    @Test
    void semanticFailureUsesJavaKnowledgeDatabaseFallback() {
        ScenicDataImportService semantic = mock(ScenicDataImportService.class);
        KnowledgeDocumentService local = mock(KnowledgeDocumentService.class);
        when(semantic.searchFragments(anyString(), eq(25), eq(12))).thenReturn(List.of());
        when(local.searchLocalKnowledge(anyString(), eq(5))).thenReturn(List.of(
                Map.of("title", "洪崖洞无障碍入口", "content", "从平街入口进入。", "source_name", "text-hongyadong")
        ));

        RagRetrievalService service = new RagRetrievalService(semantic, local);
        Map<String, Object> result = service.retrieve("洪崖洞", "重庆", Map.of(), false);

        assertEquals(true, result.get("ok"));
        assertEquals("Java 本地知识回退（关键词检索）", result.get("mode"));
        assertEquals("Java kb_document", result.get("source"));
        assertTrue(String.valueOf(result.get("reason")).contains("降级"));
        assertEquals(1, ((List<?>) result.get("facts")).size());
        assertEquals("/ai/rag/retrieve", ((Map<?, ?>) ((List<?>) result.get("citations")).get(0)).get("endpoint"));
    }

    @Test
    void unknownQueryRejectedOnQdrantPathWithPertinentFactsInspected() {
        ScenicDataImportService semantic = mock(ScenicDataImportService.class);
        KnowledgeDocumentService local = mock(KnowledgeDocumentService.class);

        // Matrix B: 语料没有对应具体事实，证据门禁应正确拒绝为 UNKNOWN

        // B1. 询问恐龙化石具体长度，但语料只有“恐龙展厅”
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("恐龙化石具体有多少米"), eq(25), eq(12)))
                .thenReturn(List.of("【重庆自然博物馆恐龙展厅】馆内设有恐龙世界展厅与地球奥秘展厅，以恐龙骨架化石闻名。"));

        // B2. 询问宠物进入全部展厅，但语料只有无障碍信息
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("带宠物进入所有展厅"), eq(25), eq(12)))
                .thenReturn(List.of("【三峡博物馆无障碍】馆内配有无障碍观光直梯与自动扶梯，服务台免费提供轮椅租借。"));

        // B3. 询问今晚临时无人机演出，但语料只有固定开放时间与亮灯时间
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("无人机表演"), eq(25), eq(12)))
                .thenReturn(List.of("【洪崖洞开放时间】景区免费开放，夜景灯光亮灯时间通常为18:30-23:00。"));

        // B4. 询问准确实时打车费，但语料只有交通路线
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("准确车费是多少"), eq(25), eq(12)))
                .thenReturn(List.of("【解放碑交通衔接】可乘坐轨道交通1/2号线，或在解放碑乘坐一日游直通大巴前往武隆。"));

        // B5. 询问明天一定不排队，但语料只有常规客流建议
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("一定不会排队"), eq(25), eq(12)))
                .thenReturn(List.of("【重庆少走路决策】建议提前了解景区人流，错峰出行。"));

        RagRetrievalService service = new RagRetrievalService(semantic, local);

        // 1. 未来确定性排队预测
        Map<String, Object> res1 = service.retrieve("重庆哪个景点明天一定不会排队？", "重庆", Map.of(), false);
        assertEquals(true, res1.get("ok"));
        assertEquals("UNKNOWN", res1.get("retrievalStatus"));
        assertEquals("FUTURE_CERTAINTY_UNSUPPORTED", res1.get("reasonCode"));
        assertEquals("QDRANT", res1.get("retrievalSource"));
        assertTrue(((List<?>) res1.get("facts")).isEmpty());

        // 2. 具体未收录尺寸
        Map<String, Object> res2 = service.retrieve("重庆自然博物馆里最大的恐龙化石具体有多少米？", "重庆", Map.of(), false);
        assertEquals("UNKNOWN", res2.get("retrievalStatus"));
        assertEquals("EXACT_DYNAMIC_VALUE_UNSUPPORTED", res2.get("reasonCode"));
        assertTrue(((List<?>) res2.get("facts")).isEmpty());

        // 3. 今晚临时演艺
        Map<String, Object> res3 = service.retrieve("洪崖洞今晚有没有确定安排无人机表演？", "重庆", Map.of(), false);
        assertEquals("UNKNOWN", res3.get("retrievalStatus"));
        assertEquals("REALTIME_DATA_REQUIRED", res3.get("reasonCode"));
        assertTrue(((List<?>) res3.get("facts")).isEmpty());

        // 4. 未收录宠物准入
        Map<String, Object> res4 = service.retrieve("重庆哪家室内展馆允许游客带宠物进入所有展厅？", "重庆", Map.of(), false);
        assertEquals("UNKNOWN", res4.get("retrievalStatus"));
        assertEquals("POLICY_NOT_IN_CORPUS", res4.get("reasonCode"));
        assertTrue(((List<?>) res4.get("facts")).isEmpty());

        // 5. 跨区精确动态打车费
        Map<String, Object> res5 = service.retrieve("从解放碑打车到武隆天生三桥，准确车费是多少？", "重庆", Map.of(), false);
        assertEquals("UNKNOWN", res5.get("retrievalStatus"));
        assertEquals("EXACT_DYNAMIC_VALUE_UNSUPPORTED", res5.get("reasonCode"));
        assertTrue(((List<?>) res5.get("facts")).isEmpty());
    }

    @Test
    void unknownQueryRejectedOnLocalFallbackPathWhenQdrantUnavailable() {
        ScenicDataImportService semantic = mock(ScenicDataImportService.class);
        KnowledgeDocumentService local = mock(KnowledgeDocumentService.class);
        when(semantic.searchFragments(anyString(), eq(25), eq(12)))
                .thenThrow(new IllegalStateException("Qdrant unavailable"));
        when(local.searchLocalKnowledge(anyString(), eq(5)))
                .thenReturn(List.of(Map.of("title", "客流提示", "content", "建议错峰出行。")));

        RagRetrievalService service = new RagRetrievalService(semantic, local);

        Map<String, Object> res = service.retrieve("重庆哪个景点明天一定不会排队？", "重庆", Map.of(), false);
        assertEquals(true, res.get("ok"));
        assertEquals("UNKNOWN", res.get("retrievalStatus"));
        assertEquals("FUTURE_CERTAINTY_UNSUPPORTED", res.get("reasonCode"));
        assertEquals("LOCAL_FALLBACK", res.get("retrievalSource"));
        assertTrue(((List<?>) res.get("facts")).isEmpty());
    }

    @Test
    void adversarialMatrixASupportedFactsAreRelevant() {
        ScenicDataImportService semantic = mock(ScenicDataImportService.class);
        KnowledgeDocumentService local = mock(KnowledgeDocumentService.class);

        // A1. “长江索道准确票价是多少？” Facts 包含具体票价
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("长江索道准确票价是多少"), eq(25), eq(12)))
                .thenReturn(List.of("【长江索道票价】单程票30元/人，往返票50元/人。"));

        // A2. “这个观景平台离地铁站多少米？” Facts 包含具体距离
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("这个观景平台离地铁站多少米"), eq(25), eq(12)))
                .thenReturn(List.of("【重庆科技馆交通】大剧院站3号口出站，步行约200米平路即达科技馆门口。"));

        // A3. “三峡博物馆周一开馆吗？” Facts 包含周一闭馆
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("三峡博物馆周一开馆吗"), eq(25), eq(12)))
                .thenReturn(List.of("【三峡博物馆开放时间】周二至周日09:00-17:00开放，周一闭馆。"));

        // A4. “今晚洪崖洞固定亮灯时间是几点？” Facts 包含固定亮灯时间
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("今晚洪崖洞固定亮灯时间是几点"), eq(25), eq(12)))
                .thenReturn(List.of("【洪崖洞夜景】夜景灯光亮灯时间通常为18:30-23:00。"));

        // A5. “某景区允许导盲犬进入吗？” Facts 明确包含导盲犬政策
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("某景区允许导盲犬进入吗"), eq(25), eq(12)))
                .thenReturn(List.of("【三峡博物馆入馆规则】展馆允许视障人士携带导盲犬进入全区。"));

        // A6. “大足石刻观光车多少钱？” Facts 包含观光车价格
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("大足石刻观光车多少钱"), eq(25), eq(12)))
                .thenReturn(List.of("【大足石刻少走路设施】必须乘坐无障碍景区观光车，往返约15元/人。"));

        RagRetrievalService service = new RagRetrievalService(semantic, local);

        Map<String, Object> a1 = service.retrieve("长江索道准确票价是多少？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", a1.get("retrievalStatus"));
        assertFalse(((List<?>) a1.get("facts")).isEmpty());

        Map<String, Object> a2 = service.retrieve("这个观景平台离地铁站多少米？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", a2.get("retrievalStatus"));
        assertFalse(((List<?>) a2.get("facts")).isEmpty());

        Map<String, Object> a3 = service.retrieve("三峡博物馆周一开馆吗？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", a3.get("retrievalStatus"));
        assertFalse(((List<?>) a3.get("facts")).isEmpty());

        Map<String, Object> a4 = service.retrieve("今晚洪崖洞固定亮灯时间是几点？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", a4.get("retrievalStatus"));
        assertFalse(((List<?>) a4.get("facts")).isEmpty());

        Map<String, Object> a5 = service.retrieve("某景区允许导盲犬进入吗？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", a5.get("retrievalStatus"));
        assertFalse(((List<?>) a5.get("facts")).isEmpty());

        Map<String, Object> a6 = service.retrieve("大足石刻观光车多少钱？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", a6.get("retrievalStatus"));
        assertFalse(((List<?>) a6.get("facts")).isEmpty());
    }

    @Test
    void adversarialMatrixCPreventBroadRuleMisfires() {
        ScenicDataImportService semantic = mock(ScenicDataImportService.class);
        KnowledgeDocumentService local = mock(KnowledgeDocumentService.class);

        // C1. “明天怎么安排三峡博物馆？”
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("明天怎么安排三峡博物馆"), eq(25), eq(12)))
                .thenReturn(List.of("【三峡博物馆行程】建议参观2小时，与对面人民大礼堂组合游览。"));

        // C2. “索道票价多少？”
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("索道票价多少"), eq(25), eq(12)))
                .thenReturn(List.of("【长江索道】单程票30元/人，往返票50元/人。"));

        // C3. “地铁站距离入口多少米？”
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("地铁站距离入口多少米"), eq(25), eq(12)))
                .thenReturn(List.of("【磁器口交通】1号线磁器口站1号口出站，步行约300米平路即达。"));

        // C4. “周一是否闭馆？”
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("周一是否闭馆"), eq(25), eq(12)))
                .thenReturn(List.of("【三峡博物馆】开放时间周二至周日，周一闭馆。"));

        // C5. “今晚几点亮灯？”
        when(semantic.searchFragments(org.mockito.ArgumentMatchers.contains("今晚几点亮灯"), eq(25), eq(12)))
                .thenReturn(List.of("【洪崖洞夜景】夜景灯光亮灯时间通常为18:30-23:00。"));

        RagRetrievalService service = new RagRetrievalService(semantic, local);

        Map<String, Object> c1 = service.retrieve("明天怎么安排三峡博物馆？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", c1.get("retrievalStatus"));

        Map<String, Object> c2 = service.retrieve("索道票价多少？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", c2.get("retrievalStatus"));

        Map<String, Object> c3 = service.retrieve("地铁站距离入口多少米？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", c3.get("retrievalStatus"));

        Map<String, Object> c4 = service.retrieve("周一是否闭馆？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", c4.get("retrievalStatus"));

        Map<String, Object> c5 = service.retrieve("今晚几点亮灯？", "重庆", Map.of(), false);
        assertEquals("RELEVANT", c5.get("retrievalStatus"));
    }
}
