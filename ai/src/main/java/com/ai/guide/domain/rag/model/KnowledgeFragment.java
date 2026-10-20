package com.ai.guide.domain.rag.model;

/** Provider-neutral fragment with explicit applicability and provenance. */
public record KnowledgeFragment(String id, String source, String content, KnowledgeScope applicability, String sourceUrl) {
    public KnowledgeFragment(String id, String source, String content, KnowledgeScope applicability) {
        this(id, source, content, applicability, "");
    }
}
