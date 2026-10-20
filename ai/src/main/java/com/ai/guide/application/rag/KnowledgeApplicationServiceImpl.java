package com.ai.guide.application.rag;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.ai.guide.domain.rag.model.KnowledgeDocumentUpload;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/**
 * 文旅知识文档管理应用服务实现
 */
@Service
public class KnowledgeApplicationServiceImpl implements KnowledgeApplicationService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeApplicationServiceImpl.class);

    private final KnowledgeDocumentService knowledgeService;

    @Autowired
    public KnowledgeApplicationServiceImpl(KnowledgeDocumentService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    private String checkAdmin() {
        if (!UserContext.isAdmin()) {
            return "需要管理员权限";
        }
        return null;
    }

    @Override
    public Result<Map<String, Object>> listDocuments(String category, String keyword, int page, int size) {
        try {
            Map<String, Object> data = knowledgeService.listDocuments(category, keyword, page, size);
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Knowledge] 列表查询失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    @Override
    public Result<List<Map<String, Object>>> getCategories() {
        try {
            List<Map<String, Object>> data = knowledgeService.getCategories();
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Knowledge] 分类查询失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    @Override
    public Result<Map<String, Object>> getDocument(String docId) {
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

    @Override
    public Result<Map<String, Object>> createDocument(String title, String category, String content, String tags, KnowledgeDocumentUpload file) {
        return createDocument(title, category, content, tags, file, null, null);
    }
    @Override public Result<Map<String, Object>> createDocument(String title, String category, String content, String tags, KnowledgeDocumentUpload file, String scope, String city) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            KnowledgeDocument doc = (scope == null && city == null ? knowledgeService.createDocument(title, category, content, tags, file, "admin") : knowledgeService.createDocument(title, category, content, tags, file, "admin", scope, city));
            return Result.success("创建成功", knowledgeService.toResponse(doc, true));
        } catch (IllegalArgumentException e) {
            return Result.error(e instanceof com.ai.guide.domain.rag.model.KnowledgeDocumentNotFoundException ? 404 : 400, e.getMessage());
        } catch (Exception e) {
            log.error("[Knowledge] 创建失败: {}", e.getMessage());
            return Result.error(500, "创建失败，请稍后重试");
        }
    }

    @Override
    public Result<Map<String, Object>> updateDocument(String docId, String title, String category, String content, String tags) {
        return updateDocument(docId, title, category, content, tags, null, null);
    }
    @Override public Result<Map<String, Object>> updateDocument(String docId, String title, String category, String content, String tags, String scope, String city) {
        String err = checkAdmin();
        if (err != null) return Result.error(403, err);
        try {
            KnowledgeDocument doc = (scope == null && city == null ? knowledgeService.updateDocument(docId, title, category, content, tags) : knowledgeService.updateDocument(docId, title, category, content, tags, scope, city));
            return Result.success("更新成功", knowledgeService.toResponse(doc, true));
        } catch (IllegalArgumentException e) {
            return Result.error(e instanceof com.ai.guide.domain.rag.model.KnowledgeDocumentNotFoundException ? 404 : 400, e.getMessage());
        } catch (Exception e) {
            log.error("[Knowledge] 更新失败: {}", e.getMessage());
            return Result.error(500, "更新失败，请稍后重试");
        }
    }

    @Override
    public Result<Void> deleteDocument(String docId) {
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

    @Override
    public Result<Map<String, Object>> triggerSync(String docId) {
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

    @Override
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
