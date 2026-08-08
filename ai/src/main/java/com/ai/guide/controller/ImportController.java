package com.ai.guide.controller;

import com.ai.guide.config.UserContext;
import com.ai.guide.model.Result;
import com.ai.guide.service.KnowledgeDocumentService;
import com.ai.guide.service.RerankService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 数据导入 + 缓存管理控制器
 *
 * 【接口列表】
 * POST /ai/import          — 上传知识库文件（Excel/Word）
 * GET  /ai/cache/stats     — 查看 Rerank 三级缓存统计
 * POST /ai/cache/clear     — 清空三级缓存
 *
 * 【缓存清空功能说明】
 * 清空 Rerank 的三级缓存（L1 精确 / L2 归一化 / L3 SimHash 语义）。
 * 适用场景：
 *   1. 导入新知识库文档后，旧缓存可能包含过时的重排结果
 *   2. 百炼 rerank 模型或策略变更后
 *   3. 缓存被"污染"（错误结果被缓存）需要手动重置
 * 操作前可通过 GET /ai/cache/stats 查看当前命中率。
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class ImportController {

    @Autowired
    private RerankService rerankService;

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    /**
     * 导入知识库文件，并同步创建文档元数据。
     * 这样 /ai/knowledge 列表与 Qdrant 中的向量碎片保持同源，上传后不会出现
     * “Qdrant 有碎片、文档管理却 Total 0”的断层。
     */
    @PostMapping("/import")
    public Result<String> uploadFile(@RequestParam("file") MultipartFile file) {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "需要管理员权限");
        }
        try {
            if (file.isEmpty()) {
                return Result.error(400, "上传失败：文件为空");
            }
            String filename = file.getOriginalFilename();
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
            e.printStackTrace();
            return Result.error(500, "导入失败: " + e.getMessage());
        }
    }

    /**
     * 查询 Rerank 三级缓存统计信息
     * 返回各层条目数、命中次数、命中率、API 调用次数
     */
    @GetMapping("/cache/stats")
    public Result<Map<String, Object>> cacheStats() {
        try {
            Map<String, Object> stats = rerankService.getCacheStats();
            return Result.success("查询成功", stats);
        } catch (Exception e) {
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }

    /**
     * 手动清空三级缓存（L1 + L2 + L3）
     * ⚠️ 清空前建议先查看 /ai/cache/stats 确认缓存状态
     */
    @PostMapping("/cache/clear")
    public Result<Void> clearCache() {
        if (!UserContext.isAdmin()) {
            return Result.error(403, "需要管理员权限");
        }
        try {
            rerankService.clearCache();
            return Result.success("Rerank 三级缓存已清空（L1/L2/L3 全部重置）", null);
        } catch (Exception e) {
            return Result.error(500, "清空失败: " + e.getMessage());
        }
    }
}
