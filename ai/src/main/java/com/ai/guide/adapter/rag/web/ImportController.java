package com.ai.guide.adapter.rag.web;

import com.ai.guide.application.rag.RagApplicationService;
import com.ai.guide.common.model.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

/**
 * 数据导入 + 缓存管理控制器（COLA 适配层）
 */
@RestController
@RequestMapping("/ai")
public class ImportController {

    private final RagApplicationService ragApplicationService;

    @Autowired
    public ImportController(RagApplicationService ragApplicationService) {
        this.ragApplicationService = ragApplicationService;
    }

    /**
     * 导入知识库文件，并同步创建文档元数据
     */
    @PostMapping("/import")
    public Result<String> uploadFile(@RequestParam("file") MultipartFile file) {
        var upload = file == null ? null : new com.ai.guide.domain.rag.model.KnowledgeDocumentUpload(
                file.getOriginalFilename(), file.getSize(), file::getBytes);
        return ragApplicationService.importFile(upload);
    }

    /**
     * 查询 Rerank 三级缓存统计信息
     */
    @GetMapping("/cache/stats")
    public Result<Map<String, Object>> cacheStats() {
        return ragApplicationService.getCacheStats();
    }

    /**
     * 手动清空三级缓存（L1 + L2 + L3）
     */
    @PostMapping("/cache/clear")
    public Result<Void> clearCache() {
        return ragApplicationService.clearImportCache();
    }
}
