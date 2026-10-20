package com.ai.guide.infrastructure.rag.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LocalKnowledgeFileStorageAdapterTest {
    @TempDir Path temporaryDirectory;

    @Test
    void savesWithGeneratedNameAndDeletesOnlyFilesUnderOwnedRoot() throws Exception {
        Path root = temporaryDirectory.resolve("uploads");
        LocalKnowledgeFileStorageAdapter storage = new LocalKnowledgeFileStorageAdapter(root.toString());
        String saved = storage.save("../../private/report.pdf", "fixture".getBytes());
        Path storedPath = Path.of(saved);

        assertTrue(storedPath.startsWith(root));
        assertEquals(".pdf", storedPath.getFileName().toString().substring(storedPath.getFileName().toString().lastIndexOf('.')));
        assertArrayEquals("fixture".getBytes(), Files.readAllBytes(storedPath));

        Path outside = temporaryDirectory.resolve("outside.txt");
        Files.writeString(outside, "preserve");
        storage.deleteOwned(outside.toString());
        assertTrue(Files.exists(outside));

        storage.deleteOwned(saved);
        assertFalse(Files.exists(storedPath));
    }
}
