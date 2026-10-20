package com.ai.guide.domain.rag.model;

import java.io.IOException;

/** Lazy upload value object so policy checks can reject size before reading bytes. */
public record KnowledgeDocumentUpload(String originalFilename, long size, ContentReader contentReader) {
    @FunctionalInterface public interface ContentReader { byte[] read() throws IOException; }

    public byte[] readBytes() throws IOException {
        if (contentReader == null) return new byte[0];
        return contentReader.read();
    }
}
