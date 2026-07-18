package com.ai.guide.controller;

import com.ai.guide.model.KnowledgeDocument;
import com.ai.guide.model.Result;
import com.ai.guide.service.KnowledgeDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理控制器
 * 对应 Python 版本: backend/app/api/v1/knowledge.py
 *
 * 原始 Python API 由 sleepearlyplease 创建
 * Java 转化版本由 summerpalace2 实现
 *
 * 接口列表：
 * GET    /ai/knowledge                 — 分页列表（分类/关键词筛选）
 * GET    /ai/knowledge/categories      — 分类统计
 * GET    /ai/knowledge/{docId}         — 文档详情
 * POST   /ai/knowledge                 — 创建文档（文本或文件）
 * PUT    /ai/knowledge/{docId}         — 更新文档
 * DELETE /ai/knowledge/{docId}         — 软删除
 * POST   /ai/knowledge/{docId}/sync    — 触发向量化
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeDocumentService knowledgeService;

    public KnowledgeController(KnowledgeDocumentService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 分页查询文档列表
     * 对应 Python: GET /knowledge?category=&keyword=&page=1&size=20
     */
    @GetMapping
    public Result<Map<String, Object>> listDocuments(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        try {
            Map<String, Object> data = knowledgeService.listDocuments(category, keyword, page, size);
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Knowledge] 列表查询失败: {}", e.getMessage());
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }

    /**
     * 获取分类统计
     * 对应 Python: GET /knowledge/categories
     */
    @GetMapping("/categories")
    public Result<List<Map<String, Object>>> getCategories() {
        try {
            List<Map<String, Object>> data = knowledgeService.getCategories();
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Knowledge] 分类查询失败: {}", e.getMessage());
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }

    /**
     * 获取文档详情
     * 对应 Python: GET /knowledge/{doc_id}
     */
    @GetMapping("/{docId}")
    public Result<Map<String, Object>> getDocument(@PathVariable String docId) {
        try {
            KnowledgeDocument doc = knowledgeService.getDocument(docId);
            if (doc == null) {
                return Result.error(404, "文档不存在");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", doc.getId());
            data.put("title", doc.getTitle());
            data.put("category", doc.getCategory());
            data.put("content", doc.getContent());
            data.put("fileUrl", doc.getFileUrl());
            data.put("status", doc.getStatus());
            data.put("vectorStatus", doc.getVectorStatus());
            data.put("tags", doc.getTags() != null ? doc.getTags() : "[]");
            data.put("chunkCount", doc.getChunkCount());
            data.put("createdAt", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "");
            data.put("updatedAt", doc.getUpdatedAt() != null ? doc.getUpdatedAt().toString() : "");
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Knowledge] 详情查询失败: {}", e.getMessage());
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }

    /**
     * 创建文档（支持文本或文件上传）
     * 对应 Python: POST /knowledge (multipart/form-data)
     */
    @PostMapping
    public Result<Map<String, Object>> createDocument(
            @RequestParam("title") String title,
            @RequestParam("category") String category,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "tags", required = false) String tags,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        try {
            KnowledgeDocument doc = knowledgeService.createDocument(title, category, content, tags, file, "admin");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", doc.getId());
            data.put("title", doc.getTitle());
            data.put("category", doc.getCategory());
            data.put("status", doc.getStatus());
            data.put("vectorStatus", doc.getVectorStatus());
            data.put("createdAt", doc.getCreatedAt() != null ? doc.getCreatedAt().toString() : "");
            return Result.success("创建成功", data);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[Knowledge] 创建失败: {}", e.getMessage());
            return Result.error(500, "创建失败: " + e.getMessage());
        }
    }

    /**
     * 更新文档
     * 对应 Python: PUT /knowledge/{doc_id}
     */
    @PutMapping("/{docId}")
    public Result<String> updateDocument(
            @PathVariable String docId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "tags", required = false) String tags) {
        try {
            knowledgeService.updateDocument(docId, title, category, content, tags);
            return Result.success("更新成功", null);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("[Knowledge] 更新失败: {}", e.getMessage());
            return Result.error(500, "更新失败: " + e.getMessage());
        }
    }

    /**
     * 软删除文档
     * 对应 Python: DELETE /knowledge/{doc_id}
     */
    @DeleteMapping("/{docId}")
    public Result<Void> deleteDocument(@PathVariable String docId) {
        try {
            knowledgeService.deleteDocument(docId);
            return Result.success("删除成功", null);
        } catch (Exception e) {
            log.error("[Knowledge] 删除失败: {}", e.getMessage());
            return Result.error(500, "删除失败: " + e.getMessage());
        }
    }

    /**
     * 触发向量化同步
     * 对应 Python: POST /knowledge/{doc_id}/sync
     */
    @PostMapping("/{docId}/sync")
    public Result<Map<String, Object>> triggerSync(@PathVariable String docId) {
        try {
            Map<String, Object> data = knowledgeService.triggerSync(docId);
            return Result.success("同步完成", data);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("[Knowledge] 同步失败: {}", e.getMessage());
            return Result.error(500, "同步失败: " + e.getMessage());
        }
    }
}
