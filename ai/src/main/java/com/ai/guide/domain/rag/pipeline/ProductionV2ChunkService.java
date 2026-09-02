package com.ai.guide.domain.rag.pipeline;

import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅对 Production V2 KnowledgeDocument 数据流进行分块。
 *
 * 这里明确不读取 FactClaim 和 QA JSON：精确事实属于 PostgreSQL，QA 元数据
 * 不得被当作旅游知识写入 Qdrant。
 */
@Service
public class ProductionV2ChunkService {

    private static final int MAX_CHUNK_CHARS = 900;

    private final ObjectMapper objectMapper;

    public ProductionV2ChunkService() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public ProductionV2ChunkService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取 knowledge_documents.json，返回带有完整来源链路且结果稳定的分块。
     */
    public List<ProductionV2Chunk> chunk(Path sourceDirectory) throws IOException {
        Path input = sourceDirectory.resolve("knowledge_documents.json");
        byte[] knowledgeDocumentBytes = Files.readAllBytes(input);
        Path sourceDocumentInput = sourceDirectory.resolve("source_documents.json");
        byte[] sourceDocumentBytes = Files.readAllBytes(sourceDocumentInput);
        JsonNode root = objectMapper.readTree(new String(knowledgeDocumentBytes, StandardCharsets.UTF_8));
        JsonNode sourceRoot = objectMapper.readTree(new String(sourceDocumentBytes, StandardCharsets.UTF_8));
        if (!root.isArray()) {
            throw new IOException("knowledge_documents.json must contain an array");
        }
        if (!sourceRoot.isArray()) {
            throw new IOException("source_documents.json must contain an array");
        }

        String snapshotVersion = snapshotVersion(knowledgeDocumentBytes, sourceDocumentBytes);
        Map<String, JsonNode> sourceDocuments = new HashMap<>();
        for (JsonNode sourceDocument : sourceRoot) {
            String sourceId = required(sourceDocument, "source_id");
            sourceDocuments.put(sourceId, sourceDocument);
        }

        List<ProductionV2Chunk> chunks = new ArrayList<>();
        for (JsonNode document : root) {
            if (!document.path("rag_eligible").asBoolean(false)) {
                continue;
            }

            String documentId = required(document, "document_id");
            String entityId = required(document, "entity_id");
            String sourceId = required(document, "source_id");
            JsonNode sourceDocument = sourceDocuments.get(sourceId);
            if (sourceDocument == null) {
                throw new IOException("KnowledgeDocument references missing SourceDocument: " + sourceId);
            }
            String sourceDocumentId = required(sourceDocument, "source_id");
            String sourceUrl = required(sourceDocument, "source_url");
            String documentSourceUrl = required(document, "source_url");
            if (!sourceUrl.equals(documentSourceUrl)) {
                throw new IOException("KnowledgeDocument/SourceDocument source_url mismatch: " + sourceId);
            }
            String publisher = firstNonBlank(sourceDocument, "publisher", "organization");
            String sourceTitle = firstNonBlank(sourceDocument, "source_title", "title");
            String title = required(document, "title");
            String content = required(document, "content");
            String trustLevel = required(document, "trust_level");
            String verificationResult = required(document, "verification_result");
            String sourceUpdatedAt = required(document, "source_updated_at");
            String accessedAt = required(document, "accessed_at");

            List<String> pieces = split(content, MAX_CHUNK_CHARS);
            for (int index = 0; index < pieces.size(); index++) {
                String chunkContent = pieces.get(index);
                String chunkId = stableId(documentId + ":" + index);
                Map<String, Object> provenance = new LinkedHashMap<>();
                provenance.put("knowledge_document_id", documentId);
                provenance.put("source_document_id", sourceDocumentId);
                provenance.put("publisher", publisher);
                provenance.put("source_url", sourceUrl);
                provenance.put("version", snapshotVersion);
                chunks.add(new ProductionV2Chunk(
                        chunkId,
                        documentId,
                        entityId,
                        sourceDocumentId,
                        sourceUrl,
                        publisher,
                        sourceTitle,
                        title,
                        trustLevel,
                        verificationResult,
                        sourceUpdatedAt,
                        accessedAt,
                        true,
                        snapshotVersion,
                        objectMapper.writeValueAsString(provenance),
                        index,
                        pieces.size(),
                        chunkContent));
            }
        }
        return List.copyOf(chunks);
    }

    private String firstNonBlank(JsonNode node, String... fields) throws IOException {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        throw new IOException("SourceDocument missing publisher/title field");
    }

    private String required(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new IOException("KnowledgeDocument missing required field: " + field);
        }
        return value.asText();
    }

    private List<String> split(String raw, int maxChars) {
        String normalized = raw.replace("\r\n", "\n").trim();
        if (normalized.isEmpty()) return List.of();
        if (normalized.length() <= maxChars) return List.of(normalized);

        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : normalized.split("\\n+")) {
            String part = paragraph.trim();
            if (part.isEmpty()) continue;
            if (current.length() > 0 && current.length() + part.length() + 1 > maxChars) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (part.length() <= maxChars) {
                if (current.length() > 0) current.append('\n');
                current.append(part);
            } else {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                for (int start = 0; start < part.length(); start += maxChars) {
                    chunks.add(part.substring(start, Math.min(start + maxChars, part.length())));
                }
            }
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    private String stableId(String value) {
        return "p2c-" + sha256(value).substring(0, 32);
    }

    private String snapshotVersion(byte[] knowledgeDocumentBytes, byte[] sourceDocumentBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(knowledgeDocumentBytes);
            digest.update((byte) 0);
            digest.update(sourceDocumentBytes);
            return "p2v-" + HexFormat.of().formatHex(digest.digest(), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record ProductionV2Chunk(
            String chunkId,
            String knowledgeDocumentId,
            String entityId,
            String sourceDocumentId,
            String sourceUrl,
            String publisher,
            String sourceTitle,
            String title,
            String trustLevel,
            String verificationResult,
            String sourceUpdatedAt,
            String accessedAt,
            boolean ragEligible,
            String version,
            String provenance,
            int chunkIndex,
            int chunkCount,
            String content) {

        /** 显式 SourceDocument 标识符的向后兼容别名。 */
        public String sourceId() {
            return sourceDocumentId;
        }
    }
}
