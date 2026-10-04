package com.ai.guide.adapter.rag.web;

import com.ai.guide.application.rag.RagApplicationService;
import com.ai.guide.common.model.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * RAG 知识检索增强与知识库碎片管理控制器（COLA 适配层）
 */
@RestController
@RequestMapping("/ai")
public class RagController {

    private final RagApplicationService ragApplicationService;

    @Autowired
    public RagController(RagApplicationService ragApplicationService) {
        this.ragApplicationService = ragApplicationService;
    }

    @PostMapping("/rag/retrieve")
    public Result<Map<String, Object>> retrieve(@RequestBody(required = false) Map<String, Object> body) {
        return ragApplicationService.retrieve(body);
    }

    @GetMapping("/rag/stats")
    public Result<Map<String, Object>> getStats() {
        return ragApplicationService.getStats();
    }

    @PutMapping("/rag/knowledge/{id}")
    public Result<Map<String, Object>> updateKnowledge(@PathVariable String id,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        return ragApplicationService.updateKnowledge(id, body);
    }

    @GetMapping("/rag/document/fragments")
    public Result<Map<String, Object>> getFragments(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        return ragApplicationService.getFragments(page, size, keyword);
    }

    @PutMapping("/rag/document/fragments/{id}")
    public Result<String> updateFragment(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ragApplicationService.updateFragment(id, body);
    }

    @DeleteMapping("/rag/document/fragments/{id}")
    public Result<String> deleteFragment(@PathVariable String id) {
        return ragApplicationService.deleteFragment(id);
    }

    @DeleteMapping("/rag/document/all")
    public Result<String> deleteAllDocuments() {
        return ragApplicationService.deleteAllDocuments();
    }

    @PostMapping("/admin/cache/clear")
    public Result<String> clearCache() {
        return ragApplicationService.clearCache();
    }
}
