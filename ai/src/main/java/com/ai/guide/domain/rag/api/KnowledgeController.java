package com.ai.guide.domain.rag.api;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

/**
 * 文旅知识文档管理控制器
 *
 * 所属领域：domain.rag（文旅知识与检索增强域）
 * 架构职责：为管理端提供文旅知识文档的全生命周期管理（创建、分类查询、详情检索、更新、软删除以及同步向量化索引）。
 *
 * 核心对外接口与关键方法：
 * 1. listDocuments：分页查询文档列表（支持分类与关键词过滤）
 *    - 参数：category（分类）、keyword（关键词）、page（页码）、size（每页条数）
 * 2. getCategories：获取当前知识库的分类分布统计
 * 3. getDocument：按文档 ID 查询单篇文档详情及切片信息
 * 4. createDocument：新增知识文档（支持纯文本或文件上传）
 * 5. updateDocument：更新文档标题、分类、正文及标签
 * 6. deleteDocument：软删除指定文档
 * 7. syncDocument：手动触发文档切片并同步更新 Qdrant 向量索引
 */
@RestController
@RequestMapping("/ai/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeDocumentService knowledgeService;

    public KnowledgeController(KnowledgeDocumentService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 写操作统一校验管理员权限（新增/修改/删除/向量同步均需 ADMIN）
     */
    private String checkAdmin() {
        if (!UserContext.isAdmin()) {
            return "需要管理员权限";
        }
        return null;
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
            return Result.error(500, "查询失败，请稍后重试");
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
            return Result.error(500, "查询失败，请稍后重试");
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
            return Result.success("查询成功", knowledgeService.toResponse(doc, true));
        } catch (Exception e) {
            log.error("[Knowledge] 详情查询失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
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
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            KnowledgeDocument doc = knowledgeService.createDocument(title, category, content, tags, file, "admin");
            return Result.success("创建成功", knowledgeService.toResponse(doc, true));
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("[Knowledge] 创建失败: {}", e.getMessage());
            return Result.error(500, "创建失败，请稍后重试");
        }
    }

    /**
     * 更新文档
     * 对应 Python: PUT /knowledge/{doc_id}
     */
    @PutMapping("/{docId}")
    public Result<Map<String, Object>> updateDocument(
            @PathVariable String docId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "tags", required = false) String tags) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            KnowledgeDocument doc = knowledgeService.updateDocument(docId, title, category, content, tags);
            return Result.success("更新成功", knowledgeService.toResponse(doc, true));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("[Knowledge] 更新失败: {}", e.getMessage());
            return Result.error(500, "更新失败，请稍后重试");
        }
    }

    /**
     * 软删除文档
     * 对应 Python: DELETE /knowledge/{doc_id}
     */
    @DeleteMapping("/{docId}")
    public Result<Void> deleteDocument(@PathVariable String docId) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            knowledgeService.deleteDocument(docId);
            return Result.success("删除成功", null);
        } catch (Exception e) {
            log.error("[Knowledge] 删除失败: {}", e.getMessage());
            return Result.error(500, "删除失败，请稍后重试");
        }
    }

    /**
     * 触发向量化同步
     * 对应 Python: POST /knowledge/{doc_id}/sync
     */
    @PostMapping("/{docId}/sync")
    public Result<Map<String, Object>> triggerSync(@PathVariable String docId) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            Map<String, Object> data = knowledgeService.triggerSync(docId);
            return Result.success("同步完成", data);
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (Exception e) {
            log.error("[Knowledge] 同步失败: {}", e.getMessage());
            return Result.error(500, "同步失败，请稍后重试");
        }
    }

    /**
     * 将早期仅写入 Qdrant 的碎片按 source 回填为文档元数据。
     * 仅管理员可调用，重复执行只会刷新既有文档的碎片数。
     */
    @PostMapping("/backfill")
    public Result<Map<String, Object>> backfillDocuments() {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            return Result.success("向量文档回填完成", knowledgeService.backfillDocumentsFromVectors());
        } catch (Exception e) {
            log.error("[Knowledge] 向量文档回填失败: {}", e.getMessage());
            return Result.error(500, "回填失败，请稍后重试");
        }
    }
}
