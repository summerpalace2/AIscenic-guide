package com.ai.guide.controller;

import com.ai.guide.config.UserContext;
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

    @GetMapping("/rag/document/fragments")
    public Result<Map<String, Object>> getFragments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            return Result.success("查询成功", scenicDataImportService.listFragments(page, size, keyword));
        } catch (Exception e) {
            return Result.error(500, "查询碎片失败: " + e.getMessage());
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
            return Result.error(500, "更新碎片失败: " + e.getMessage());
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
            return Result.error(500, "删除碎片失败: " + e.getMessage());
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
        if (!UserContext.isAdmin()) return Result.error(403, "无管理员权限");
        try {
            rerankService.clearCache();
            return Result.success("缓存已清空", "L1/L2/L3 全部重置");
        } catch (Exception e) {
            return Result.error(500, "清空失败: " + e.getMessage());
        }
    }
}
