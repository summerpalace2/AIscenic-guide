package com.ai.guide.infrastructure.rag.storage;

import com.ai.guide.domain.rag.gateway.KnowledgeFileStoragePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Stores knowledge uploads beneath one canonical root and refuses out-of-root deletion. */
@Component
public class LocalKnowledgeFileStorageAdapter implements KnowledgeFileStoragePort {
    private final Path root;

    public LocalKnowledgeFileStorageAdapter(@Value("${knowledge.upload-dir:uploads/knowledge}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override public String save(String originalFilename, byte[] content) throws IOException {
        Files.createDirectories(root);
        String extension = extension(originalFilename);
        Path destination = root.resolve(UUID.randomUUID() + extension).normalize();
        if (!destination.getParent().equals(root)) throw new IOException("Invalid upload destination");
        Files.write(destination, content == null ? new byte[0] : content);
        return destination.toString();
    }

    @Override public String basename(String storedLocation) {
        if (storedLocation == null || storedLocation.isBlank()) return "";
        return Path.of(storedLocation).getFileName().toString();
    }

    @Override public void deleteOwned(String storedLocation) {
        if (storedLocation == null || storedLocation.isBlank()) return;
        try {
            Path candidate = Path.of(storedLocation).toAbsolutePath().normalize();
            if (!candidate.startsWith(root) || candidate.equals(root)) return;
            Files.deleteIfExists(candidate);
        } catch (Exception ignored) { /* cleanup is best effort after tombstoning */ }
    }

    private String extension(String name) {
        if (name == null) return "";
        String leaf = Path.of(name).getFileName().toString();
        int dot = leaf.lastIndexOf('.');
        if (dot < 0 || dot == leaf.length() - 1) return "";
        String suffix = leaf.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        return suffix.length() <= 16 ? suffix : "";
    }
}
