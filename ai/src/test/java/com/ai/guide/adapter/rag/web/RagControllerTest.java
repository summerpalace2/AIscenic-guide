package com.ai.guide.adapter.rag.web;

import com.ai.guide.application.rag.RagApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class RagControllerTest {

    private RagApplicationService ragApplicationService;
    private RagController controller;

    @BeforeEach
    void setUp() {
        ragApplicationService = Mockito.mock(RagApplicationService.class);
        controller = new RagController(ragApplicationService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testRetrieveSuccess() {
        when(ragApplicationService.retrieve(anyMap()))
                .thenReturn(Result.success("检索完成", Map.of("facts", "ok")));

        Result<Map<String, Object>> result = controller.retrieve(Map.of("query", "洪崖洞"));
        assertEquals(200, result.getCode());
        assertEquals("检索完成", result.getMessage());
    }

    @Test
    void testGetStats() {
        when(ragApplicationService.getStats())
                .thenReturn(Result.success("查询成功", Map.of("totalChunks", 10)));

        Result<Map<String, Object>> result = controller.getStats();
        assertEquals(200, result.getCode());
    }

    @Test
    void testUpdateKnowledge() {
        when(ragApplicationService.updateKnowledge(eq("doc1"), anyMap()))
                .thenReturn(Result.success("更新成功", Map.of("id", "doc1")));

        Result<Map<String, Object>> result = controller.updateKnowledge("doc1", Map.of("title", "新标题"));
        assertEquals(200, result.getCode());
    }

    @Test
    void testGetFragments() {
        when(ragApplicationService.getFragments(1, 20, "test"))
                .thenReturn(Result.success("查询成功", Map.of("list", "items")));

        Result<Map<String, Object>> result = controller.getFragments(1, 20, "test");
        assertEquals(200, result.getCode());
    }

    @Test
    void testUpdateFragment() {
        when(ragApplicationService.updateFragment(eq("f1"), anyMap()))
                .thenReturn(Result.success("更新成功", "碎片文本已更新"));

        Result<String> result = controller.updateFragment("f1", Map.of("content", "新内容"));
        assertEquals(200, result.getCode());
        assertEquals("碎片文本已更新", result.getData());
    }

    @Test
    void testDeleteFragment() {
        when(ragApplicationService.deleteFragment("f1"))
                .thenReturn(Result.success("删除成功", "碎片已删除"));

        Result<String> result = controller.deleteFragment("f1");
        assertEquals(200, result.getCode());
    }

    @Test
    void testDeleteAllDocuments() {
        when(ragApplicationService.deleteAllDocuments())
                .thenReturn(Result.success("知识库已清空", "所有碎片已删除"));

        Result<String> result = controller.deleteAllDocuments();
        assertEquals(200, result.getCode());
    }

    @Test
    void testClearCache() {
        when(ragApplicationService.clearCache())
                .thenReturn(Result.success("缓存已清空", "L1/L2/L3 全部重置"));

        Result<String> result = controller.clearCache();
        assertEquals(200, result.getCode());
    }
}
