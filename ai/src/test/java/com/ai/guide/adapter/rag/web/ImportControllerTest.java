package com.ai.guide.adapter.rag.web;

import com.ai.guide.application.rag.RagApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockMultipartFile;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ImportControllerTest {

    private RagApplicationService ragApplicationService;
    private ImportController controller;

    @BeforeEach
    void setUp() {
        ragApplicationService = Mockito.mock(RagApplicationService.class);
        controller = new ImportController(ragApplicationService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void testUploadFile() {
        MockMultipartFile file = new MockMultipartFile("file", "test.xlsx", "application/octet-stream", "data".getBytes());
        when(ragApplicationService.importFile(any()))
                .thenReturn(Result.success("文件导入成功: test.xlsx", "test.xlsx"));

        Result<String> result = controller.uploadFile(file);
        assertEquals(200, result.getCode());
        assertEquals("test.xlsx", result.getData());
    }

    @Test
    void testCacheStats() {
        when(ragApplicationService.getCacheStats())
                .thenReturn(Result.success("查询成功", Map.of("hits", 100)));

        Result<Map<String, Object>> result = controller.cacheStats();
        assertEquals(200, result.getCode());
    }

    @Test
    void testClearCache() {
        when(ragApplicationService.clearImportCache())
                .thenReturn(Result.success("Rerank 三级缓存已清空（L1/L2/L3 全部重置）", null));

        Result<Void> result = controller.clearCache();
        assertEquals(200, result.getCode());
    }
}
