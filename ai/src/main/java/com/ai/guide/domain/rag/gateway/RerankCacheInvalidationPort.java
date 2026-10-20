package com.ai.guide.domain.rag.gateway;

/** Capability for invalidating derived retrieval ranking results after source data changes. */
public interface RerankCacheInvalidationPort {
    void invalidateAll();
}
