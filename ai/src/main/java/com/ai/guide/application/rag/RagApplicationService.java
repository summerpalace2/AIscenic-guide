package com.ai.guide.application.rag;

import com.ai.guide.common.model.Result;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

/**
 * RAG 知识检索与缓存管理应用服务契约
 */
public interface RagApplicationService {

    Result<Map<String, Object>> retrieve(Map<String, Object> body);

    Result<Map<String, Object>> getStats();

    Result<Map<String, Object>> updateKnowledge(String id, Map<String, Object> body);

    Result<Map<String, Object>> getFragments(int page, int size, String keyword);

    Result<String> updateFragment(String id, Map<String, String> body);

    Result<String> deleteFragment(String id);

    Result<String> deleteAllDocuments();

    Result<String> clearCache();

    Result<String> importFile(MultipartFile file);

    Result<Map<String, Object>> getCacheStats();

    Result<Void> clearImportCache();
}
