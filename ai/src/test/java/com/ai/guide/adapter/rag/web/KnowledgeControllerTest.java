package com.ai.guide.adapter.rag.web;

import com.ai.guide.application.rag.KnowledgeApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class KnowledgeControllerTest {

    private KnowledgeApplicationService knowledgeApplicationService;
    private KnowledgeController controller;

    @BeforeEach
    void setUp() {
        knowledgeApplicationService = Mockito.mock(KnowledgeApplicationService.class);
        controller = new KnowledgeController(knowledgeApplicationService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testListDocuments() {
        when(knowledgeApplicationService.listDocuments(eq("attraction"), eq("洪崖洞"), eq(1), eq(20)))
                .thenReturn(Result.success("查询成功", Map.of("total", 1)));

        Result<Map<String, Object>> result = controller.listDocuments("attraction", "洪崖洞", 1, 20);
        assertEquals(200, result.getCode());
    }

    @Test
    void testGetCategories() {
        when(knowledgeApplicationService.getCategories())
                .thenReturn(Result.success("查询成功", List.of(Map.of("category", "attraction"))));

        Result<List<Map<String, Object>>> result = controller.getCategories();
        assertEquals(200, result.getCode());
    }

    @Test
    void testGetDocument() {
        when(knowledgeApplicationService.getDocument("doc1"))
                .thenReturn(Result.success("查询成功", Map.of("id", "doc1")));

        Result<Map<String, Object>> result = controller.getDocument("doc1");
        assertEquals(200, result.getCode());
    }

    @Test
    void testCreateDocument() {
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain", "hello".getBytes());
        when(knowledgeApplicationService.createDocument(eq("标题"), eq("category"), any(), any(), any()))
                .thenReturn(Result.success("创建成功", Map.of("id", "doc_new")));

        Result<Map<String, Object>> result = controller.createDocument("标题", "category", "正文", "tag1", file);
        assertEquals(200, result.getCode());
    }

    @Test
    void testUpdateDocument() {
        when(knowledgeApplicationService.updateDocument(eq("doc1"), eq("新标题"), any(), any(), any()))
                .thenReturn(Result.success("更新成功", Map.of("id", "doc1")));

        Result<Map<String, Object>> result = controller.updateDocument("doc1", "新标题", "cat", "内容", "tags");
        assertEquals(200, result.getCode());
    }

    @Test
    void testDeleteDocument() {
        when(knowledgeApplicationService.deleteDocument("doc1"))
                .thenReturn(Result.success("删除成功", null));

        Result<Void> result = controller.deleteDocument("doc1");
        assertEquals(200, result.getCode());
    }

    @Test
    void testTriggerSync() {
        when(knowledgeApplicationService.triggerSync("doc1"))
                .thenReturn(Result.success("同步完成", Map.of("chunks", 5)));

        Result<Map<String, Object>> result = controller.triggerSync("doc1");
        assertEquals(200, result.getCode());
    }

    @Test
    void testBackfillDocuments() {
        when(knowledgeApplicationService.backfillDocuments())
                .thenReturn(Result.success("向量文档回填完成", Map.of("backfilled", 3)));

        Result<Map<String, Object>> result = controller.backfillDocuments();
        assertEquals(200, result.getCode());
    }
}
