package com.ai.guide.domain.search.gateway;

import com.ai.guide.domain.search.model.LocalKnowledgeHit;
import java.util.List;

/** Search-domain capability for retrieving locally curated knowledge. */
public interface LocalKnowledgeSearchPort {
    List<LocalKnowledgeHit> search(String query, int limit);
}
