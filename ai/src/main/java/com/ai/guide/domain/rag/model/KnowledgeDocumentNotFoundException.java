package com.ai.guide.domain.rag.model;

public final class KnowledgeDocumentNotFoundException extends IllegalArgumentException {
    public KnowledgeDocumentNotFoundException(String id) { super("Document not found: " + id); }
}
