package com.ai.guide.application.rag;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
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

class KnowledgeApplicationServiceImplTest {

    private KnowledgeDocumentService knowledgeService;
    private KnowledgeApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        knowledgeService = Mockito.mock(KnowledgeDocumentService.class);
        service = new KnowledgeApplicationServiceImpl(knowledgeService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testListDocuments() {
        when(knowledgeService.listDocuments("cat", "kw", 1, 20)).thenReturn(Map.of("total", 5));
        Result<Map<String, Object>> result = service.listDocuments("cat", "kw", 1, 20);
        assertEquals(200, result.getCode());
    }

    @Test
    void testGetCategories() {
        when(knowledgeService.getCategories()).thenReturn(List.of(Map.of("cat", 1)));
        Result<List<Map<String, Object>>> result = service.getCategories();
        assertEquals(200, result.getCode());
    }

    @Test
    void testGetDocument() {
        // Not found -> 404
        when(knowledgeService.getDocument("missing")).thenReturn(null);
        Result<Map<String, Object>> notFound = service.getDocument("missing");
        assertEquals(404, notFound.getCode());

        // Found -> 200
        KnowledgeDocument doc = new KnowledgeDocument();
        when(knowledgeService.getDocument("doc1")).thenReturn(doc);
        when(knowledgeService.toResponse(doc, true)).thenReturn(Map.of("id", "doc1"));
        Result<Map<String, Object>> found = service.getDocument("doc1");
        assertEquals(200, found.getCode());
    }

    @Test
    void testCreateDocumentAdminAndValidation() throws Exception {
        // Non-admin -> 403
        Result<Map<String, Object>> forbidden = service.createDocument("t", "c", null, null, null);
        assertEquals(403, forbidden.getCode());
        assertEquals("需要管理员权限", forbidden.getMessage());

        UserContext.set("admin1", "admin", "ADMIN");

        // Invalid args -> 400
        when(knowledgeService.createDocument(eq("bad"), any(), any(), any(), any(), eq("admin")))
                .thenThrow(new IllegalArgumentException("标题不能为空"));
        Result<Map<String, Object>> badRequest = service.createDocument("bad", "c", null, null, null);
        assertEquals(400, badRequest.getCode());

        // Success -> 200
        KnowledgeDocument doc = new KnowledgeDocument();
        when(knowledgeService.createDocument(eq("ok"), any(), any(), any(), any(), eq("admin"))).thenReturn(doc);
        when(knowledgeService.toResponse(doc, true)).thenReturn(Map.of("id", "new_doc"));
        Result<Map<String, Object>> success = service.createDocument("ok", "c", null, null, null);
        assertEquals(200, success.getCode());
    }

    @Test
    void testUpdateDocumentAdminAndNotFound() {
        // Non-admin -> 403
        Result<Map<String, Object>> forbidden = service.updateDocument("doc1", "t", "c", null, null);
        assertEquals(403, forbidden.getCode());

        UserContext.set("admin1", "admin", "ADMIN");

        // Not found -> 404
        when(knowledgeService.updateDocument(eq("missing"), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("文档不存在: missing"));
        Result<Map<String, Object>> notFound = service.updateDocument("missing", "t", "c", null, null);
        assertEquals(404, notFound.getCode());

        // Success -> 200
        KnowledgeDocument doc = new KnowledgeDocument();
        when(knowledgeService.updateDocument(eq("doc1"), any(), any(), any(), any())).thenReturn(doc);
        when(knowledgeService.toResponse(doc, true)).thenReturn(Map.of("id", "doc1"));
        Result<Map<String, Object>> success = service.updateDocument("doc1", "t", "c", null, null);
        assertEquals(200, success.getCode());
    }

    @Test
    void testDeleteDocument() {
        Result<Void> forbidden = service.deleteDocument("doc1");
        assertEquals(403, forbidden.getCode());

        UserContext.set("admin1", "admin", "ADMIN");
        Result<Void> success = service.deleteDocument("doc1");
        assertEquals(200, success.getCode());
    }

    @Test
    void testTriggerSync() {
        Result<Map<String, Object>> forbidden = service.triggerSync("doc1");
        assertEquals(403, forbidden.getCode());

        UserContext.set("admin1", "admin", "ADMIN");
        when(knowledgeService.triggerSync("missing")).thenThrow(new IllegalArgumentException("文档不存在"));
        Result<Map<String, Object>> notFound = service.triggerSync("missing");
        assertEquals(404, notFound.getCode());

        when(knowledgeService.triggerSync("doc1")).thenReturn(Map.of("synced", 1));
        Result<Map<String, Object>> success = service.triggerSync("doc1");
        assertEquals(200, success.getCode());
    }

    @Test
    void testBackfillDocuments() {
        Result<Map<String, Object>> forbidden = service.backfillDocuments();
        assertEquals(403, forbidden.getCode());

        UserContext.set("admin1", "admin", "ADMIN");
        when(knowledgeService.backfillDocumentsFromVectors()).thenReturn(Map.of("total", 10));
        Result<Map<String, Object>> success = service.backfillDocuments();
        assertEquals(200, success.getCode());
    }
}
