package com.ai.guide.domain.rag.gateway;

import java.io.IOException;

/** Controlled source-file storage for uploaded knowledge documents. */
public interface KnowledgeFileStoragePort {
    String save(String originalFilename, byte[] content) throws IOException;
    String basename(String storedLocation);
    void deleteOwned(String storedLocation);
}
