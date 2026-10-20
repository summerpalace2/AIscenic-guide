package com.ai.guide.domain.rag.gateway;

import java.util.List;

/** Provider-neutral reranking capability. Returned positions refer to the input documents. */
public interface RerankProviderPort {
    List<Integer> rerank(String query, List<String> documents, int topN);
}
