package com.ai.guide.application.search;

import com.ai.guide.domain.rag.service.KnowledgeDocumentService;
import com.ai.guide.domain.search.gateway.LocalKnowledgeSearchPort;
import com.ai.guide.domain.search.model.LocalKnowledgeHit;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Composes the RAG-owned knowledge query into the search-domain contract. */
@Component
public class LocalKnowledgeSearchAdapter implements LocalKnowledgeSearchPort {
    private final KnowledgeDocumentService knowledgeDocumentService;

    public LocalKnowledgeSearchAdapter(KnowledgeDocumentService knowledgeDocumentService) {
        this.knowledgeDocumentService = knowledgeDocumentService;
    }

    @Override public List<LocalKnowledgeHit> search(String query, int limit) {
        List<Map<String, Object>> hits = knowledgeDocumentService.searchLocalKnowledge(query, limit);
        if (hits == null) return List.of();
        return hits.stream().map(hit -> new LocalKnowledgeHit(
                String.valueOf(hit.getOrDefault("title", "")),
                String.valueOf(hit.getOrDefault("content", "")))).toList();
    }
}
