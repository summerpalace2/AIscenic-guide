package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.ai.guide.domain.rag.model.KnowledgeScope;
import com.ai.guide.domain.rag.model.KnowledgeDocumentUpload;
import com.ai.guide.domain.rag.gateway.RerankCacheInvalidationPort;
import com.ai.guide.domain.rag.gateway.KnowledgeIndexPort;
import com.ai.guide.domain.rag.gateway.KnowledgeFileStoragePort;
import com.ai.guide.domain.rag.gateway.KnowledgeDocumentStorePort;
import com.ai.guide.domain.rag.gateway.KnowledgeRuntimePolicyPort;
import com.ai.guide.domain.taxonomy.gateway.RagTaxonomyPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 知识文档 Service
 * 对应 Python 版本: backend/app/services/knowledge_service.py
 *
 * 原始 Python Service 由 sleepearlyplease 创建
 * Java 转化版本由 summerpalace2 实现
 */
@Service
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final KnowledgeDocumentStorePort documentStore;
    private final KnowledgeIndexPort knowledgeIndex;
    private final KnowledgeFileStoragePort fileStorage;
    /** Spring 托管的有界索引执行器，避免上传/编辑路径裸建线程。 */
    private final Executor indexExecutor;
    private final RerankCacheInvalidationPort rerankCache;
    private final KnowledgeRuntimePolicyPort runtimePolicy;
    private final int retrievalMaxCandidates;
    private final int retrievalExcerptChars;
    private final RagQueryExpander queryExpander;
    private final RagTaxonomyPort taxonomy;
    @Value("${knowledge.index.recovery.lease-ms:300000}")
    private long indexLeaseMillis = 300_000L;
    private boolean syncEnabled() { return runtimePolicy == null || runtimePolicy.syncEnabled(); }
    private long indexRevision(String id) {
        Long revision = documentStore.indexRevision(id);
        return revision == null ? -1L : revision;
    }
    private void requireActive(KnowledgeDocument doc, String id) {
        if (doc == null || "archived".equals(doc.getStatus())) throw new com.ai.guide.domain.rag.model.KnowledgeDocumentNotFoundException(id);
    }

    @Autowired
    public KnowledgeDocumentService(KnowledgeDocumentStorePort documentStore,
                                    KnowledgeIndexPort knowledgeIndex,
                                    KnowledgeFileStoragePort fileStorage,
                                    @org.springframework.beans.factory.annotation.Qualifier("knowledgeIndexExecutor") Executor indexExecutor,
                                    RerankCacheInvalidationPort rerankCache,
                                    KnowledgeRuntimePolicyPort runtimePolicy,
                                    RagTaxonomyPort taxonomy) {
        this.documentStore = documentStore;
        this.knowledgeIndex = knowledgeIndex;
        this.fileStorage = Objects.requireNonNull(fileStorage);
        this.indexExecutor = indexExecutor;
        this.rerankCache = rerankCache;
        this.runtimePolicy = Objects.requireNonNull(runtimePolicy);
        this.retrievalMaxCandidates = runtimePolicy.retrievalMaxCandidates();
        this.retrievalExcerptChars = runtimePolicy.retrievalExcerptChars();
        this.queryExpander = new RagQueryExpander(taxonomy);
        this.taxonomy = taxonomy == null ? RagTaxonomyPort.empty() : taxonomy;
    }

    public KnowledgeDocumentService(KnowledgeDocumentStorePort documentStore, KnowledgeIndexPort knowledgeIndex,
                                    KnowledgeFileStoragePort fileStorage, Executor indexExecutor,
                                    RerankCacheInvalidationPort rerankCache, KnowledgeRuntimePolicyPort runtimePolicy) {
        this(documentStore, knowledgeIndex, fileStorage, indexExecutor, rerankCache, runtimePolicy, RagTaxonomyPort.empty());
    }

    /**
     * 分页查询文档列表
     */
    public Map<String, Object> listDocuments(String category, String keyword, int page, int size) {
        KnowledgeDocumentStorePort.Page documentPage = documentStore.list(category, keyword, page, size);
        List<KnowledgeDocument> items = documentPage.items();

        // 构造返回（content 只返回前 100 字符摘要）
        List<Map<String, Object>> records = new ArrayList<>();
        for (KnowledgeDocument d : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("title", d.getTitle());
            m.put("category", d.getCategory());
            m.put("scope", d.getScope()); m.put("city", d.getCity());
            m.put("contentSnippet", d.getContent() != null && d.getContent().length() > 100 ? d.getContent().substring(0, 100) : d.getContent());
            m.put("status", d.getStatus());
            m.put("vectorStatus", d.getVectorStatus());
            m.put("chunkCount", d.getChunkCount());
            m.put("tags", parseTags(d.getTags()));
            m.put("sourceName", resolveSourceName(d));
            m.put("fileUrl", d.getFileUrl());
            m.put("canRefresh", isRefreshable(d));
            m.put("createdAt", d.getCreatedAt() != null ? d.getCreatedAt().format(DT_FMT) : "");
            m.put("updatedAt", d.getUpdatedAt() != null ? d.getUpdatedAt().format(DT_FMT) : "");
            records.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", documentPage.total());
        result.put("page", page);
        result.put("size", size);
        result.put("items", records);
        return result;
    }

    /**
     * 面向 RAG 降级路径的 Java 本地关键词检索。
     * 只读取 Java-owned kb_document，不读取 Node 语料文件。
     */
    public List<Map<String, Object>> searchLocalKnowledge(String query, int limit) {
        return searchLocalKnowledge(query, limit, KnowledgeScope.global());
    }
    public List<Map<String, Object>> searchLocalKnowledge(String query, int limit, KnowledgeScope scope) {
        int safeLimit = Math.min(20, Math.max(1, limit));
        List<String> terms = retrievalTerms(query).stream().limit(12).toList();
        int excerptChars = Math.max(256, Math.min(16384, retrievalExcerptChars));
        String needle = terms.isEmpty() ? "" : terms.get(0);
        List<Map<String, Object>> rows = documentStore.searchCandidates(terms, needle, excerptChars,
                Math.max(safeLimit, Math.min(2000, retrievalMaxCandidates)), scope);
        List<Map<String, Object>> ranked = new ArrayList<>();
        int windowBudget = Math.max(20, safeLimit * 4);
        for (Map<String, Object> row : rows) {
            String title = String.valueOf(row.getOrDefault("title", ""));
            String content = String.valueOf(row.getOrDefault("content", ""));
            if (windowBudget-- > 0)
                content = relevantExcerpt(String.valueOf(row.get("id")), terms, content, excerptChars);
            String tags = String.valueOf(row.getOrDefault("tags", ""));
            String searchable = (title + " " + content + " " + tags).toLowerCase(Locale.ROOT);
            int score = queryExpander.score(query, title, tags, content);
            // A SQL match may occur beyond the bounded excerpt; retain that candidate.
            if (!terms.isEmpty()) score = Math.max(1, score);
            for (String term : terms) {
                if (title.toLowerCase(Locale.ROOT).contains(term)) score += 5;
                if (tags.toLowerCase(Locale.ROOT).contains(term)) score += 3;
                if (content.toLowerCase(Locale.ROOT).contains(term)) score += 1;
            }
            if (terms.isEmpty() || score > 0) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                copy.put("content", content);
                copy.put("retrievalScore", score);
                copy.put("searchable", searchable);
                ranked.add(copy);
            }
        }
        // Generic questions such as opening times occur in many documents.
        // Give evidence unique to a candidate more weight than repeated boilerplate.
        for (String term : terms) {
            long frequency = ranked.stream().filter(r -> String.valueOf(r.get("searchable")).contains(term)).count();
            if (frequency == 0) continue;
            int evidenceWeight = (int) (term.length() * (ranked.size() + 1L) / frequency);
            for (Map<String, Object> row : ranked) if (String.valueOf(row.get("searchable")).contains(term))
                row.put("retrievalScore", ((Number) row.get("retrievalScore")).intValue() + evidenceWeight);
        }
        ranked.sort((left, right) -> Integer.compare(
                ((Number) right.getOrDefault("retrievalScore", 0)).intValue(),
                ((Number) left.getOrDefault("retrievalScore", 0)).intValue()));
        List<Map<String, Object>> selected = new ArrayList<>(ranked.subList(0, Math.min(safeLimit, ranked.size())));
        return selected;
    }

    /** Probe bounded matched windows, so an incidental mention in a preface cannot hide the useful paragraph. */
    private String relevantExcerpt(String id, List<String> terms, String initial, int size) {
        String best = initial;
        int bestScore = excerptScore(best, terms);
        int maximumScore = terms.stream().mapToInt(t -> Math.max(1, t.length())).sum();
        if (bestScore >= maximumScore) return best;
        for (String term : terms.stream().sorted(Comparator.comparingInt(String::length).reversed()).limit(4).toList()) {
            int offset = 1;
            for (int occurrence = 0; occurrence < 3; occurrence++) {
                Integer relative = documentStore.relativeMatch(id, offset, term);
                if (relative == null || relative <= 0) break;
                int position = offset + relative - 1;
                String window = documentStore.contentWindow(id, Math.max(1, position - size / 4), size);
                int score = excerptScore(window, terms);
                if (score > bestScore) { best = window; bestScore = score; }
                if (bestScore >= maximumScore) return best;
                offset = position + term.length();
            }
        }
        return best;
    }

    private int excerptScore(String text, List<String> terms) {
        if (text == null) return 0;
        String normalized = text.toLowerCase(Locale.ROOT);
        return terms.stream().filter(normalized::contains).mapToInt(t -> Math.max(1, t.length())).sum();
    }

    private List<String> retrievalTerms(String query) {
        String normalized = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        if (normalized.isBlank()) return List.of();
        Set<String> terms = new LinkedHashSet<>();
        java.util.regex.Matcher chinese = java.util.regex.Pattern
                .compile("[\\u4e00-\\u9fff]{2,}").matcher(normalized);
        while (chinese.find()) {
            String run = chinese.group();
            terms.add(run);
            for (int index = 0; index + 1 < run.length(); index++) {
                terms.add(run.substring(index, index + 2));
            }
        }
        java.util.regex.Matcher latin = java.util.regex.Pattern
                .compile("[a-z0-9][a-z0-9_-]{1,}").matcher(normalized);
        while (latin.find()) terms.add(latin.group());
        terms.removeAll(new LinkedHashSet<>(taxonomy.getRagStopwords()));
        return new ArrayList<>(terms);
    }

    /**
     * 获取所有分类及其文档数
     */
    public List<Map<String, Object>> getCategories() {
        return documentStore.categories().stream().map(category -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", category.category());
            m.put("label", categoryLabel(category.category()));
            m.put("count", category.count());
            return m;
        }).toList();
    }

    private String categoryLabel(String category) {
        return switch (category) {
            case "history" -> "历史文化";
            case "culture" -> "人文艺术";
            case "faq" -> "常见问题";
            case "notice" -> "游览须知";
            case "other" -> "其它资料";
            default -> category;
        };
    }

    /**
     * 获取文档详情
     */
    public KnowledgeDocument getDocument(String docId) {
        return documentStore.findById(docId);
    }

    /**
     * 创建文档（支持文本或文件上传）
     */
    public KnowledgeDocument createDocument(String title, String category, String content,
                                             String tags, KnowledgeDocumentUpload file, String userId) throws IOException {
        return createDocument(title, category, content, tags, file, userId, null, null);
    }
    public KnowledgeDocument createDocument(String title, String category, String content, String tags,
            KnowledgeDocumentUpload file, String userId, String scope, String city) throws IOException {
        KnowledgeScope applicability = KnowledgeScope.input(scope, city, taxonomy);
        if (documentStore.activeTitleExists(title, applicability)) {
            throw new IllegalArgumentException("已存在同名文档: " + title);
        }

        String id = UUID.randomUUID().toString();
        String fileUrl = "";
        String fileMd5 = "";

        // 文件上传处理
        if (file != null && file.size() > 0) {
            runtimePolicy.checkUploadSize(file.size());
            byte[] fileBytes = file.readBytes();
            fileMd5 = md5(fileBytes);
            // MD5 去重
            if (documentStore.activeFileHashExists(fileMd5, applicability)) {
                throw new IllegalArgumentException("文件已存在（相同 MD5）");
            }
            fileUrl = fileStorage.save(file.originalFilename(), fileBytes);
        }

        // 文件文档使用保存后的唯一文件名，纯文本使用稳定的文档 ID，二者都可作为 Qdrant source。
        String sourceName = fileUrl.isEmpty() ? "text-" + id : fileStorage.basename(fileUrl);

        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocument document = new KnowledgeDocument();
        document.setScope(applicability.scope()); document.setCity(applicability.city());
        document.setId(id); document.setTitle(title); document.setCategory(category);
        document.setContent(content != null ? content : ""); document.setTags(tags != null ? tags : "[]");
        document.setFileUrl(fileUrl); document.setFileMd5(fileMd5); document.setSourceName(sourceName);
        document.setStatus("active"); document.setVectorStatus("pending"); document.setChunkCount(0);
        document.setIndexRequestMode(!fileUrl.isEmpty() ? "FILE" : (content != null && !content.isBlank() ? "TEXT" : ""));
        document.setCreatedBy(userId != null ? userId : ""); document.setCreatedAt(now); document.setUpdatedAt(now);
        documentStore.insert(document);

        log.info("[Knowledge] 创建文档 id={} title={}", id, title);

        // 异步触发向量化；纯文本直接用编辑内容生成碎片，文件则按原文件解析。
        if (!document.getIndexRequestMode().isEmpty() && syncEnabled()) submitIndexTask(id);

        return getDocument(id);
    }

    /**
     * 更新文档元数据，并在正文变更时以管理员保存的内容重新生成向量。
     * 文件文档的“刷新”由 triggerSync 单独处理，始终以原文件为准。
     */
    public KnowledgeDocument updateDocument(String docId, String title, String category,
                                             String content, String tags) {
        return updateDocument(docId, title, category, content, tags, null, null);
    }
    public KnowledgeDocument updateDocument(String docId, String title, String category, String content, String tags, String scope, String city) {
        return documentStore.withDocumentLock(docId, () -> updateLocked(docId, title, category, content, tags, scope, city));
    }
    private KnowledgeDocument updateLocked(String docId, String title, String category, String content, String tags, String scope, String city) {
        KnowledgeDocument existing = getDocument(docId);
        requireActive(existing, docId);
        KnowledgeScope previous = applicability(existing);
        KnowledgeScope next = scope == null && city == null ? previous
                : KnowledgeScope.input(scope == null ? previous.scope() : scope,
                        city == null ? (scope != null && !"CITY".equalsIgnoreCase(scope) ? "" : previous.city()) : city, taxonomy);
        boolean scopeChanged = !previous.equals(next);
        boolean contentChanged = content != null && !Objects.equals(content, existing.getContent());
        String sourceName = resolveSourceName(existing);

        documentStore.update(docId, title, category, content, tags, sourceName,
                contentChanged || scopeChanged, LocalDateTime.now().format(DT_FMT), scopeChanged ? next : null);
        if (contentChanged || scopeChanged) {
            String effectiveContent = contentChanged ? content : existing.getContent();
            String mode;
            if (contentChanged) mode = effectiveContent == null || effectiveContent.isBlank() ? "DELETE" : "TEXT";
            else if (isPersistedMode(existing.getIndexRequestMode())) mode = existing.getIndexRequestMode();
            else if (existing.getFileUrl() != null && !existing.getFileUrl().isBlank()) mode = "FILE";
            else mode = effectiveContent == null || effectiveContent.isBlank() ? "DELETE" : "TEXT";
            documentStore.requestIndex(docId, mode, false);
            rerankCache.invalidateAll();
            if ("DELETE".equals(mode) || syncEnabled()) submitIndexTask(docId);
        }
        log.info("[Knowledge] 更新文档 id={}", docId);

        return getDocument(docId);
    }

    /** Archive first; the durable DELETE request finishes vector removal. File cleanup is best effort. */
    public void deleteDocument(String docId) {
        documentStore.withDocumentLock(docId, () -> { deleteLocked(docId); return null; });
    }
    private void deleteLocked(String docId) {
        KnowledgeDocument existing = getDocument(docId);
        requireActive(existing, docId);
        // Preserve a canonical persistent source tombstone, including old file records
        // whose source_name was empty. Historical backfill cannot restore this source.
        documentStore.archive(docId, resolveSourceName(existing), LocalDateTime.now().format(DT_FMT));
        rerankCache.invalidateAll();
        submitIndexTask(docId);
        log.info("[Knowledge] 软删除文档 id={}", docId);
    }

    /**
     * 触发向量化同步
     */
    public Map<String, Object> triggerSync(String docId) {
        runtimePolicy.requireSyncEnabled();
        documentStore.withDocumentLock(docId, () -> {
            KnowledgeDocument doc = getDocument(docId);
            requireActive(doc, docId);
            if (!isRefreshable(doc)) throw new IllegalArgumentException("该历史文档没有原始文件，不能刷新；可编辑正文后重新向量化");
            String mode = doc.getFileUrl() != null && !doc.getFileUrl().isBlank() ? "FILE" : "TEXT";
            documentStore.requestIndex(docId, mode, true);
            return null;
        });
        if (!executeIndex(docId, null, true)) throw new IllegalStateException("同步期间文档已修改或由其他实例接手，请查看最新状态");

        KnowledgeDocument updated = getDocument(docId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", updated.getId());
        result.put("vectorStatus", updated.getVectorStatus());
        result.put("chunkCount", updated.getChunkCount());
        return result;
    }

    private KnowledgeIndexPort.PreparedIndex prepareTextWithSource(String sourceName, String content, KnowledgeDocument doc) throws Exception {
        String sourceUrl = doc.getSourceUrl();
        return sourceUrl == null || sourceUrl.isBlank()
                ? knowledgeIndex.prepareText(sourceName, content, applicability(doc))
                : knowledgeIndex.prepareText(sourceName, content, applicability(doc), sourceUrl);
    }

    private boolean isPersistedMode(String mode) {
        return "TEXT".equals(mode) || "FILE".equals(mode) || "DELETE".equals(mode);
    }

    /** The persisted request, not this in-memory callback, is the work record. */
    private void submitIndexTask(String docId) {
        long submittedRevision = indexRevision(docId);
        documentStore.afterCommit(() -> {
            try { indexExecutor.execute(() -> executeIndex(docId, submittedRevision, false)); }
            catch (RejectedExecutionException e) { log.warn("[Knowledge] 索引队列已满，保留持久请求 id={}", docId); }
        });
    }

    /** Explicit bounded drain, also used by the opt-in recovery scheduler. */
    public int recoverDue(int limit) {
        if (limit <= 0) return 0;
        int handled = 0;
        for (String id : documentStore.dueIndexIds(System.currentTimeMillis(), Math.min(limit, 100), syncEnabled())) {
            if (executeIndex(id, null, false)) handled++;
        }
        return handled;
    }

    private boolean executeIndex(String docId, Long expectedRevision, boolean reportFailure) {
        KnowledgeDocumentStorePort.IndexClaim claim = documentStore.withDocumentLock(docId,
                () -> expectedRevision != null && indexRevision(docId) != expectedRevision ? null
                        : documentStore.claimIndex(docId, System.currentTimeMillis(), Math.max(1_000L, indexLeaseMillis)));
        if (claim == null) return false;
        KnowledgeDocument doc = claim.document();
        try {
            if (!"DELETE".equals(claim.mode()) && !syncEnabled()) {
                // Runtime disabled after enqueue: release into delayed retry without calling a supplier.
                documentStore.withDocumentLock(docId, () -> documentStore.finishIndexFailure(docId,
                        claim.revision(), claim.token(), System.currentTimeMillis() + 60_000L));
                return false;
            }
            String sourceName = resolveSourceName(doc);
            KnowledgeIndexPort.PreparedIndex prepared = switch (claim.mode()) {
                case "FILE" -> knowledgeIndex.prepareFile(doc.getFileUrl(), applicability(doc));
                case "TEXT" -> prepareTextWithSource(sourceName, doc.getContent(), doc);
                case "DELETE" -> null;
                default -> throw new IllegalStateException("Unsupported persisted index mode");
            };
            boolean published = documentStore.withDocumentLock(docId, () -> {
                if (!documentStore.ownsIndexClaim(docId, claim.revision(), claim.token(), System.currentTimeMillis())) return false;
                int chunks;
                if ("DELETE".equals(claim.mode())) {
                    knowledgeIndex.deleteSource(sourceName);
                    if ("archived".equals(doc.getStatus())) deleteStoredFile(doc.getFileUrl());
                    chunks = 0;
                } else chunks = prepared.publish(claim.revision());
                if (documentStore.finishIndexSuccess(docId, claim.revision(), claim.token(), sourceName, chunks) != 1)
                    throw new IllegalStateException("索引发布时文档版本已失效");
                return true;
            });
            if (published) log.info("[Knowledge] 索引请求完成 id={} mode={}", docId, claim.mode());
            return published;
        } catch (Exception e) {
            long backoff = Math.min(1_800_000L, 1_000L << Math.min(10, Math.max(0, claim.attempt() - 1)));
            documentStore.withDocumentLock(docId, () -> documentStore.finishIndexFailure(docId,
                    claim.revision(), claim.token(), System.currentTimeMillis() + backoff));
            log.error("[Knowledge] 索引请求失败 id={} mode={} attempt={}: {}", docId, claim.mode(), claim.attempt(), e.getMessage());
            if (reportFailure) throw new RuntimeException("向量化失败: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 将已有 Qdrant source 回填为 kb_document，解决旧版本“碎片存在、文档列表为空”的断层。
     * 回填不伪造原始文件，后续编辑会以回填的文本摘要重建向量。
     */
    public Map<String, Object> backfillDocumentsFromVectors() {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<Map<String, Object>> summaries = knowledgeIndex.listSourceSummaries();
        Set<String> archivedSources = new HashSet<>();
        for (KnowledgeDocumentStorePort.ArchivedSource archived : documentStore.archivedSources()) {
            String source = archived.sourceName();
            if (source == null || source.isBlank()) {
                source = archived.fileUrl() != null && !archived.fileUrl().isBlank()
                        ? fileStorage.basename(archived.fileUrl()) : archived.title();
            }
            if (source != null) archivedSources.add(source);
        }
        String now = LocalDateTime.now().format(DT_FMT);
        for (Map<String, Object> summary : summaries) {
            String sourceName = String.valueOf(summary.getOrDefault("sourceName", ""));
            if (sourceName.isBlank()) continue;
            int chunkCount = ((Number) summary.getOrDefault("chunkCount", 0)).intValue();
            String content = String.valueOf(summary.getOrDefault("content", ""));
            // Archived rows are persistent deletion tombstones. This check also
            // covers legacy file records whose source_name was never populated.
            if (archivedSources.contains(sourceName)) { skipped++; continue; }
            KnowledgeScope scope;
            try { scope = KnowledgeScope.input(String.valueOf(summary.getOrDefault("scope", "UNSPECIFIED")), String.valueOf(summary.getOrDefault("city", "")), taxonomy); }
            catch (IllegalArgumentException invalid) { scope = KnowledgeScope.unspecified(); }
            Long revision = summary.get("indexRevision") instanceof Number number ? number.longValue() : null;
            switch (documentStore.backfill(sourceName, content, chunkCount, now, scope, revision)) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case SKIPPED -> skipped++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sources", summaries.size());
        result.put("created", created);
        result.put("updated", updated);
        result.put("skipped", skipped);
        return result;
    }

    /** 将实体转换为前端可直接使用的 camelCase 响应。 */
    private KnowledgeScope applicability(KnowledgeDocument d) {
        return new KnowledgeScope(d.getScope(), d.getCity());
    }
    public Map<String, Object> toResponse(KnowledgeDocument document, boolean includeContent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", document.getId());
        data.put("title", document.getTitle());
        data.put("category", document.getCategory());
        data.put("scope", document.getScope());
        data.put("city", document.getCity());
        data.put("contentSnippet", document.getContent() != null && document.getContent().length() > 100
                ? document.getContent().substring(0, 100) : document.getContent());
        if (includeContent) data.put("content", document.getContent());
        data.put("fileUrl", document.getFileUrl());
        data.put("sourceName", resolveSourceName(document));
        data.put("canRefresh", isRefreshable(document));
        data.put("status", document.getStatus());
        data.put("vectorStatus", document.getVectorStatus());
        data.put("tags", parseTags(document.getTags()));
        data.put("chunkCount", document.getChunkCount());
        data.put("createdAt", document.getCreatedAt() != null ? document.getCreatedAt().format(DT_FMT) : "");
        data.put("updatedAt", document.getUpdatedAt() != null ? document.getUpdatedAt().format(DT_FMT) : "");
        return data;
    }

    // ───────── 工具方法 ─────────

    /** 兼容历史库中损坏或为空的标签 JSON，始终向前端返回字符串数组。 */
    private List<String> parseTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) return List.of();
        try {
            return objectMapper.readValue(rawTags, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    /** 为新旧文档解析可稳定定位 Qdrant 碎片的 source。 */
    private String resolveSourceName(KnowledgeDocument document) {
        if (document.getSourceName() != null && !document.getSourceName().isBlank()) {
            return document.getSourceName();
        }
        if (document.getFileUrl() != null && !document.getFileUrl().isBlank()) {
            return fileStorage.basename(document.getFileUrl());
        }
        return document.getTitle() != null && !document.getTitle().isBlank()
                ? document.getTitle() : "text-" + document.getId();
    }

    /** 回填文档没有可追溯原文件，禁止“刷新”覆盖其现有向量。 */
    private boolean isRefreshable(KnowledgeDocument document) {
        return document.getFileUrl() != null && !document.getFileUrl().isBlank()
                || !"system-backfill".equals(document.getCreatedBy());
    }

    /** Best-effort cleanup limited to the managed upload directory. */
    private void deleteStoredFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) return;
        fileStorage.deleteOwned(fileUrl);
    }

    private String md5(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
