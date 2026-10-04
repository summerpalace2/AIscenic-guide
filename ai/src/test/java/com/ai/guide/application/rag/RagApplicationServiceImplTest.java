package com.ai.guide.application.rag;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.domain.rag.service.RagRetrievalService;
import com.ai.guide.domain.rag.service.RerankService;
import com.ai.guide.domain.rag.service.ScenicDataImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RagApplicationServiceImplTest {

    private ScenicDataImportService scenicDataImportService;
    private RerankService rerankService;
    private RagRetrievalService ragRetrievalService;
    private KnowledgeDocumentService knowledgeDocumentService;
    private RagApplicationServiceImpl service;

    @BeforeEach
    void setUp() {
        scenicDataImportService = Mockito.mock(ScenicDataImportService.class);
        rerankService = Mockito.mock(RerankService.class);
        ragRetrievalService = Mockito.mock(RagRetrievalService.class);
        knowledgeDocumentService = Mockito.mock(KnowledgeDocumentService.class);
        service = new RagApplicationServiceImpl(
                scenicDataImportService,
                rerankService,
                ragRetrievalService,
                knowledgeDocumentService
        );
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testRetrieveSuccessAndNullFallback() {
        when(ragRetrievalService.retrieve(eq(""), eq("重庆"), anyMap(), eq(false)))
                .thenReturn(Map.of("facts", "ok"));

        Result<Map<String, Object>> result = service.retrieve(null);
        assertEquals(200, result.getCode());
        assertEquals("检索完成", result.getMessage());

        // Error handling
        when(ragRetrievalService.retrieve(anyString(), anyString(), anyMap(), anyBoolean()))
                .thenThrow(new RuntimeException("boom"));
        Result<Map<String, Object>> errorResult = service.retrieve(Map.of("query", "test"));
        assertEquals(500, errorResult.getCode());
        assertEquals("检索失败，请稍后重试", errorResult.getMessage());
    }

    @Test
    void testGetStatsAdminCheck() {
        // Non-admin -> 403
        Result<Map<String, Object>> forbidden = service.getStats();
        assertEquals(403, forbidden.getCode());
        assertEquals("无管理员权限", forbidden.getMessage());

        // Admin -> 200
        UserContext.set("admin1", "admin", "ADMIN");
        when(scenicDataImportService.countDocuments()).thenReturn(100L);
        when(rerankService.getCacheStats()).thenReturn(Map.of());
        when(scenicDataImportService.countDistinctSources()).thenReturn(5);
        when(ragRetrievalService.status()).thenReturn(Map.of());

        Result<Map<String, Object>> success = service.getStats();
        assertEquals(200, success.getCode());
    }

    @Test
    void testUpdateKnowledge() {
        // Non-admin -> 403
        Result<Map<String, Object>> forbidden = service.updateKnowledge("doc1", Map.of());
        assertEquals(403, forbidden.getCode());

        UserContext.set("admin1", "admin", "ADMIN");
        when(knowledgeDocumentService.updateDocument(eq("doc1"), any(), any(), any(), any()))
                .thenReturn(new KnowledgeDocument());
        when(knowledgeDocumentService.toResponse(any(), eq(true)))
                .thenReturn(Map.of("id", "doc1"));

        Result<Map<String, Object>> success = service.updateKnowledge("doc1", Map.of("title", "t"));
        assertEquals(200, success.getCode());

        // Not found
        when(knowledgeDocumentService.updateDocument(eq("doc2"), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("文档不存在: doc2"));
        Result<Map<String, Object>> notFound = service.updateKnowledge("doc2", Map.of());
        assertEquals(404, notFound.getCode());
    }

    @Test
    void testFragmentOperations() {
        UserContext.set("admin1", "admin", "ADMIN");

        when(scenicDataImportService.listFragments(1, 20, null)).thenReturn(Map.of("fragments", "list"));
        Result<Map<String, Object>> listResult = service.getFragments(1, 20, null);
        assertEquals(200, listResult.getCode());

        Result<String> updateResult = service.updateFragment("f1", Map.of("content", "new text"));
        assertEquals(200, updateResult.getCode());

        Result<String> deleteResult = service.deleteFragment("f1");
        assertEquals(200, deleteResult.getCode());

        Result<String> deleteAll = service.deleteAllDocuments();
        assertEquals(200, deleteAll.getCode());
    }

    @Test
    void testCacheOperations() {
        UserContext.set("admin1", "admin", "ADMIN");

        Result<String> clearResult = service.clearCache();
        assertEquals(200, clearResult.getCode());

        when(rerankService.getCacheStats()).thenReturn(Map.of("hits", 10));
        Result<Map<String, Object>> cacheStats = service.getCacheStats();
        assertEquals(200, cacheStats.getCode());

        Result<Void> clearImport = service.clearImportCache();
        assertEquals(200, clearImport.getCode());
    }

    @Test
    void testImportFile() {
        // Non-admin -> 403
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "text/plain", "abc".getBytes());
        Result<String> forbidden = service.importFile(file);
        assertEquals(403, forbidden.getCode());
        assertEquals("需要管理员权限", forbidden.getMessage());

        UserContext.set("admin1", "admin", "ADMIN");

        // Empty file -> 400
        MockMultipartFile emptyFile = new MockMultipartFile("file", "test.xlsx", "text/plain", new byte[0]);
        Result<String> emptyResult = service.importFile(emptyFile);
        assertEquals(400, emptyResult.getCode());
        assertEquals("上传失败：文件为空", emptyResult.getMessage());

        // Valid file -> 200
        Result<String> success = service.importFile(file);
        assertEquals(200, success.getCode());
        assertEquals("test.xlsx", success.getData());
    }
}
