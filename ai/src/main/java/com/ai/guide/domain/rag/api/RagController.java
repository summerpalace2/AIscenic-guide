package com.ai.guide.domain.rag.api;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.domain.rag.service.RagRetrievalService;
import com.ai.guide.domain.rag.service.RerankService;
import com.ai.guide.domain.rag.service.ScenicDataImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 知识检索增强与知识库碎片管理控制器
 *
 * 所属领域：domain.rag（文旅知识与检索增强域）
 * 架构职责：封装文旅知识的向量语义召回、百炼 Rerank 重排打分、知识库健康指标监控以及知识碎片的维护。
 *
 * 核心对外接口与关键方法：
 * 1. retrieve：执行多维度 RAG 知识检索（结合 Qdrant 向量检索与三级重排缓存）
 *    - 参数 body：包含 query（查询语句/Prompt）、city（目标城市，默认重庆）、constraints（偏好约束字典）、deep（是否深度检索）
 *    - 返回值：结构化事实列表、核验状态与引文溯源
 * 2. getStats：获取向量库碎片总数、多级重排缓存命中率以及向量检索健康状态
 * 3. updateKnowledge：管理员更新指定知识文档的内容与标签
 * 4. getFragments：管理员分页浏览向量碎片列表
 * 5. updateFragment：管理员修改单个向量碎片文本
 * 6. deleteFragment：管理员删除指定向量碎片
 * 7. clearCache：清空重排三级缓存
 */
@RestController
@RequestMapping("/ai")
public class RagController {

    @Autowired
    private ScenicDataImportService scenicDataImportService;

    @Autowired
    private RerankService rerankService;

    @Autowired
    private RagRetrievalService ragRetrievalService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @PostMapping("/rag/retrieve")
    public Result<Map<String, Object>> retrieve(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> input = body == null ? Map.of() : body;
        String query = String.valueOf(input.getOrDefault("query", input.getOrDefault("prompt", "")));
        String city = String.valueOf(input.getOrDefault("city", "重庆"));
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

    @GetMapping("/rag/stats")
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

    @PutMapping("/rag/knowledge/{id}")
    public Result<Map<String, Object>> updateKnowledge(@PathVariable String id,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        Map<String, Object> input = body == null ? Map.of() : body;
        try {
            KnowledgeDocument document = knowledgeDocumentService.updateDocument(
                    id,
                    input.get("title") == null ? null : String.valueOf(input.get("title")),
                    input.get("category") == null ? null : String.valueOf(input.get("category")),
                    input.get("content") == null ? null : String.valueOf(input.get("content")),
                    input.get("tags") == null ? null : String.valueOf(input.get("tags"))
            );
            return Result.success("更新成功", knowledgeDocumentService.toResponse(document, true));
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "更新知识文档失败，请稍后重试");
        }
    }

    @GetMapping("/rag/document/fragments")
    public Result<Map<String, Object>> getFragments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            return Result.success("查询成功", scenicDataImportService.listFragments(page, size, keyword));
        } catch (Exception e) {
            return Result.error(500, "查询碎片失败，请稍后重试");
        }
    }

    @PutMapping("/rag/document/fragments/{id}")
    public Result<String> updateFragment(@PathVariable String id, @RequestBody Map<String, String> body) {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            scenicDataImportService.updateFragment(id, body.get("content"));
            return Result.success("更新成功", "碎片文本已更新");
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error(500, "更新碎片失败，请稍后重试");
        }
    }

    @DeleteMapping("/rag/document/fragments/{id}")
    public Result<String> deleteFragment(@PathVariable String id) {
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

    @DeleteMapping("/rag/document/all")
    public Result<String> deleteAllDocuments() {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            scenicDataImportService.deleteAllDocuments();
            return Result.success("知识库已清空", "所有碎片已删除");
        } catch (Exception e) {
            return Result.error(500, "清空失败，请稍后重试");
        }
    }

    @PostMapping("/admin/cache/clear")
    public Result<String> clearCache() {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            rerankService.clearCache();
            return Result.success("缓存已清空", "L1/L2/L3 全部重置");
        } catch (Exception e) {
            return Result.error(500, "清空失败，请稍后重试");
        }
    }
}
