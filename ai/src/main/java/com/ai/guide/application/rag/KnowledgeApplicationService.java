package com.ai.guide.application.rag;

import com.ai.guide.common.model.Result;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;
import java.util.Map;

/**
 * 文旅知识文档管理应用服务契约
 */
public interface KnowledgeApplicationService {

    Result<Map<String, Object>> listDocuments(String category, String keyword, int page, int size);

    Result<List<Map<String, Object>>> getCategories();

    Result<Map<String, Object>> getDocument(String docId);

    Result<Map<String, Object>> createDocument(String title, String category, String content, String tags, MultipartFile file);

    Result<Map<String, Object>> updateDocument(String docId, String title, String category, String content, String tags);

    Result<Void> deleteDocument(String docId);

    Result<Map<String, Object>> triggerSync(String docId);

    Result<Map<String, Object>> backfillDocuments();
}
