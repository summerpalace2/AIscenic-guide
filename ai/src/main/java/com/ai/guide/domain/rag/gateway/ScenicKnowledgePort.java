package com.ai.guide.domain.rag.gateway;

import java.util.List;
import java.util.Map;

/** Provider-neutral semantic knowledge retrieval and fragment administration capability. */
public interface ScenicKnowledgePort extends KnowledgeIndexPort {
    List<com.ai.guide.domain.rag.model.KnowledgeFragment> searchScoped(String query, int candidateLimit,
            int rerankLimit, boolean deep, com.ai.guide.domain.rag.model.KnowledgeScope scope);
    String collectionName();
    String queryKnowledge(String query);
    String queryKnowledge(String query, long timeoutMs);
    List<String> searchFragments(String query, int candidateLimit, int rerankLimit);
    List<String> searchFragmentsDeep(String query, int candidateLimit, int rerankLimit);
    long countDocuments();
    int countDistinctSources();
    void deleteAllDocuments();
    Map<String, Object> listFragments(int page, int size, String keyword);
    void updateFragment(String id, String content);
    void deleteFragment(String id);
}
