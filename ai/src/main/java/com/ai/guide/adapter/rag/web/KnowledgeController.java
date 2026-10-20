package com.ai.guide.adapter.rag.web;

import com.ai.guide.application.rag.KnowledgeApplicationService;
import com.ai.guide.common.model.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

/**
 * 文旅知识文档管理控制器（COLA 适配层）
 */
@RestController
@RequestMapping("/ai/knowledge")
public class KnowledgeController {

    private final KnowledgeApplicationService knowledgeApplicationService;

    @Autowired
    public KnowledgeController(KnowledgeApplicationService knowledgeApplicationService) {
        this.knowledgeApplicationService = knowledgeApplicationService;
    }

    /**
     * 分页查询文档列表
     */
    @GetMapping
    public Result<Map<String, Object>> listDocuments(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return knowledgeApplicationService.listDocuments(category, keyword, page, size);
    }

    /**
     * 获取分类统计
     */
    @GetMapping("/categories")
    public Result<List<Map<String, Object>>> getCategories() {
        return knowledgeApplicationService.getCategories();
    }

    /**
     * 获取文档详情
     */
    @GetMapping("/{docId}")
    public Result<Map<String, Object>> getDocument(@PathVariable String docId) {
        return knowledgeApplicationService.getDocument(docId);
    }

    /**
     * 创建文档（支持文本或文件上传）
     */
    @PostMapping
    public Result<Map<String, Object>> createDocument(
            @RequestParam("title") String title,
            @RequestParam("category") String category,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "city", required = false) String city) {
        com.ai.guide.domain.rag.model.KnowledgeDocumentUpload upload = file == null ? null
                : new com.ai.guide.domain.rag.model.KnowledgeDocumentUpload(
                        file.getOriginalFilename(), file.getSize(), file::getBytes);
        return knowledgeApplicationService.createDocument(title, category, content, tags, upload, scope, city);
    }

    public Result<Map<String, Object>> createDocument(String title, String category, String content, String tags, MultipartFile file) {
        var upload = file == null ? null : new com.ai.guide.domain.rag.model.KnowledgeDocumentUpload(file.getOriginalFilename(), file.getSize(), file::getBytes);
        return knowledgeApplicationService.createDocument(title, category, content, tags, upload);
    }

    public Result<Map<String, Object>> updateDocument(String docId, String title, String category, String content, String tags) {
        return knowledgeApplicationService.updateDocument(docId, title, category, content, tags);
    }

    /**
     * 更新文档
     */
    @PutMapping("/{docId}")
    public Result<Map<String, Object>> updateDocument(
            @PathVariable String docId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "city", required = false) String city) {
        return knowledgeApplicationService.updateDocument(docId, title, category, content, tags, scope, city);
    }

    /**
     * 软删除文档
     */
    @DeleteMapping("/{docId}")
    public Result<Void> deleteDocument(@PathVariable String docId) {
        return knowledgeApplicationService.deleteDocument(docId);
    }

    /**
     * 触发向量化同步
     */
    @PostMapping("/{docId}/sync")
    public Result<Map<String, Object>> triggerSync(@PathVariable String docId) {
        return knowledgeApplicationService.triggerSync(docId);
    }

    /**
     * 将早期仅写入 Qdrant 的碎片按 source 回填为文档元数据
     */
    @PostMapping("/backfill")
    public Result<Map<String, Object>> backfillDocuments() {
        return knowledgeApplicationService.backfillDocuments();
    }
}
