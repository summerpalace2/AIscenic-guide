package com.ai.guide.controller;

import com.ai.guide.model.Result;
import com.ai.guide.service.RerankService;
import com.ai.guide.service.ScenicDataImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class RagController {

    @Autowired
    private ScenicDataImportService scenicDataImportService;

    @Autowired
    private RerankService rerankService;

    @GetMapping("/rag/stats")
    public Result<Map<String, Object>> getStats() {
        try {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalChunks", scenicDataImportService.countDocuments());
            stats.put("cacheStats", rerankService.getCacheStats());
            stats.put("sourceCount", scenicDataImportService.countDistinctSources());
            return Result.success("查询成功", stats);
        } catch (Exception e) {
            return Result.error(500, "查询失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/rag/document/all")
    public Result<String> deleteAllDocuments() {
        try {
            scenicDataImportService.deleteAllDocuments();
            return Result.success("知识库已清空", "所有碎片已删除");
        } catch (Exception e) {
            return Result.error(500, "清空失败: " + e.getMessage());
        }
    }

    @PostMapping("/admin/cache/clear")
    public Result<String> clearCache() {
        try {
            rerankService.clearCache();
            return Result.success("缓存已清空", "L1/L2/L3 全部重置");
        } catch (Exception e) {
            return Result.error(500, "清空失败: " + e.getMessage());
        }
    }
}