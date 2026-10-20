package com.ai.guide.domain.rag.gateway;

import com.ai.guide.domain.rag.model.KnowledgeScope;

import java.util.Map;
import java.util.Set;

/** Current SQL authority for managed vector sources at retrieval time. */
public interface KnowledgeIndexVisibilityPort {
    /** Returns only unique, active sources whose vector publication completed. */
    Map<String, IndexedSource> visibleSources(Set<String> sourceNames);

    record IndexedSource(KnowledgeScope scope, long revision) { }
}
