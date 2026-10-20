package com.ai.guide.application.rag;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.domain.rag.model.KnowledgeDocumentUpload;
import com.ai.guide.domain.rag.service.RagRetrievalService;
import com.ai.guide.domain.rag.service.RerankService;
import com.ai.guide.domain.rag.gateway.ScenicKnowledgePort;
import com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 知识检索与缓存管理应用服务实现
 */
@Service
public class RagApplicationServiceImpl implements RagApplicationService {

    private final ScenicKnowledgePort scenicDataImportService;
    private final RerankService rerankService;
    private final RagRetrievalService ragRetrievalService;
    private final KnowledgeDocumentService knowledgeDocumentService;
    private final RagTaxonomyPort taxonomy;

    @Autowired
    public RagApplicationServiceImpl(ScenicKnowledgePort scenicDataImportService,
                                     RerankService rerankService,
                                     RagRetrievalService ragRetrievalService,
                                     KnowledgeDocumentService knowledgeDocumentService,
                                     RagTaxonomyPort taxonomy) {
        this.scenicDataImportService = scenicDataImportService;
        this.rerankService = rerankService;
        this.ragRetrievalService = ragRetrievalService;
        this.knowledgeDocumentService = knowledgeDocumentService;
        this.taxonomy = taxonomy;
    }

    @Override
    public Result<Map<String, Object>> retrieve(Map<String, Object> body) {
        Map<String, Object> input = body == null ? Map.of() : body;
        String query = String.valueOf(input.getOrDefault("query", input.getOrDefault("prompt", "")));
        String requestedCity = String.valueOf(input.getOrDefault("city", "")).trim();
        String city = requestedCity;
        boolean deep = Boolean.parseBoolean(String.valueOf(input.getOrDefault("deep", false)));
        Map<String, Object> constraints = new LinkedHashMap<>();
        Object rawConstraints = input.get("constraints");
        if (rawConstraints instanceof Map<?, ?> rawMap) {
            rawMap.forEach((key, value) -> constraints.put(String.valueOf(key), value));
        }
        try {
            return Result.success("检索完成", ragRetrievalService.retrieve(query, city, constraints, deep));
        } catch (Exception e) {
            return Result.error(500, "检索失败，请稍后重试");
        }
    }

    @Override
    public Result<Map<String, Object>> getStats() {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalChunks", scenicDataImportService.countDocuments());
            stats.put("cacheStats", rerankService.getCacheStats());
            stats.put("sourceCount", scenicDataImportService.countDistinctSources());
            stats.put("retrieval", ragRetrievalService.status());
            return Result.success("查询成功", stats);
        } catch (Exception e) {
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    @Override
    public Result<Map<String, Object>> updateKnowledge(String id, Map<String, Object> body) {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        Map<String, Object> input = body == null ? Map.of() : body;
        try {
            KnowledgeDocument document = knowledgeDocumentService.updateDocument(
                    id,
                    input.get("title") == null ? null : String.valueOf(input.get("title")),
                    input.get("category") == null ? null : String.valueOf(input.get("category")),
                    input.get("content") == null ? null : String.valueOf(input.get("content")),
                    input.get("tags") == null ? null : String.valueOf(input.get("tags")),
                    input.get("scope") == null ? null : String.valueOf(input.get("scope")),
                    input.get("city") == null ? null : String.valueOf(input.get("city"))
            );
            return Result.success("更新成功", knowledgeDocumentService.toResponse(document, true));
        } catch (IllegalArgumentException e) {
            return Result.error(e instanceof com.ai.guide.domain.rag.model.KnowledgeDocumentNotFoundException ? 404 : 400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "更新知识文档失败，请稍后重试");
        }
    }

    @Override
    public Result<Map<String, Object>> getFragments(int page, int size, String keyword) {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            return Result.success("查询成功", scenicDataImportService.listFragments(page, size, keyword));
        } catch (Exception e) {
            return Result.error(500, "查询碎片失败，请稍后重试");
        }
    }

    @Override
    public Result<String> updateFragment(String id, Map<String, String> body) {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            scenicDataImportService.updateFragment(id, body != null ? body.get("content") : null);
            return Result.success("更新成功", "碎片文本已更新");
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "更新碎片失败，请稍后重试");
        }
    }

    @Override
    public Result<String> deleteFragment(String id) {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            scenicDataImportService.deleteFragment(id);
            return Result.success("删除成功", "碎片已删除");
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "删除碎片失败，请稍后重试");
        }
    }

    @Override
    public Result<String> deleteAllDocuments() {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            scenicDataImportService.deleteAllDocuments();
            return Result.success("知识库已清空", "所有碎片已删除");
        } catch (Exception e) {
            return Result.error(500, "清空失败，请稍后重试");
        }
    }

    @Override
    public Result<String> clearCache() {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            rerankService.clearCache();
            return Result.success("缓存已清空", "L1/L2/L3 全部重置");
        } catch (Exception e) {
            return Result.error(500, "清空失败，请稍后重试");
        }
    }

    @Override
    public Result<String> importFile(KnowledgeDocumentUpload file) {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "需要管理员权限");
        }
        try {
            if (file == null || file.size() <= 0) {
                return Result.error(400, "上传失败：文件为空");
            }
            String filename = file.originalFilename();
            String title = filename == null || filename.isBlank() ? "未命名文档" : filename;
            knowledgeDocumentService.createDocument(
                    title,
                    "other",
                    null,
                    "[]",
                    file,
                    UserContext.getUserId());
            return Result.success("文件导入成功: " + filename, filename);
        } catch (Exception e) {
            return Result.error(500, "导入失败，请稍后重试");
        }
    }

    @Override
    public Result<Map<String, Object>> getCacheStats() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "需要管理员权限");
        }
        try {
            Map<String, Object> stats = rerankService.getCacheStats();
            return Result.success("查询成功", stats);
        } catch (Exception e) {
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    @Override
    public Result<Void> clearImportCache() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "需要管理员权限");
        }
        try {
            rerankService.clearCache();
            return Result.success("Rerank 三级缓存已清空（L1/L2/L3 全部重置）", null);
        } catch (Exception e) {
            return Result.error(500, "清空失败，请稍后重试");
        }
    }
}
