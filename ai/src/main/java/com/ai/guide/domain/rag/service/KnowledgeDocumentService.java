package com.ai.guide.domain.rag.service;

import com.ai.guide.domain.rag.model.KnowledgeDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
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

    private final JdbcTemplate jdbcTemplate;
    private final ScenicDataImportService scenicDataImportService;
    /** Spring 托管的有界索引执行器，避免上传/编辑路径裸建线程。 */
    private final Executor indexExecutor;
    // Stable stripes are shared by mutations and callbacks, never removed while work is pending.
    private final ThreadLocal<Long> activeIndexRevision = new ThreadLocal<>();
    private static final Object[] DOCUMENT_LOCKS = new Object[128];
    static { Arrays.setAll(DOCUMENT_LOCKS, index -> new Object()); }
    private Object documentLock(String id) { return DOCUMENT_LOCKS[(id.hashCode() & Integer.MAX_VALUE) % DOCUMENT_LOCKS.length]; }
    private com.ai.guide.domain.admin.service.RuntimeSettingsPolicy runtimePolicy;
    @Autowired
    public void setRuntimePolicy(com.ai.guide.domain.admin.service.RuntimeSettingsPolicy policy) { this.runtimePolicy = policy; }
    private boolean syncEnabled() { return runtimePolicy == null || runtimePolicy.isKnowledgeSyncEnabled(); }
    private long indexRevision(String id) {
        Long revision = jdbcTemplate.queryForObject("SELECT index_revision FROM kb_document WHERE id = ?", Long.class, id);
        return revision == null ? -1L : revision;
    }
    private boolean currentIndex(String id, long revision) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM kb_document WHERE id = ? AND index_revision = ? AND (status IS NULL OR status <> 'archived')", Integer.class, id, revision);
        return count != null && count == 1;
    }
    private void requireActive(KnowledgeDocument doc, String id) {
        if (doc == null || "archived".equals(doc.getStatus())) throw new IllegalArgumentException("Document not found: " + id);
    }

    @Value("${knowledge.upload-dir:uploads/knowledge}")
    private String uploadDirectory;
    @Value("${knowledge.retrieval.max-candidates:200}")
    private int retrievalMaxCandidates = 200;
    @Value("${knowledge.retrieval.excerpt-chars:4096}")
    private int retrievalExcerptChars = 4096;
    private volatile String retrievalPositionFunction;
    private String retrievalPositionFunction() {
        if (retrievalPositionFunction == null) {
            retrievalPositionFunction = jdbcTemplate.getDataSource() == null ? "INSTR"
                    : jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                    connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres") ? "STRPOS" : "INSTR");
        }
        return retrievalPositionFunction;
    }

    @Autowired
    public KnowledgeDocumentService(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                                    ScenicDataImportService scenicDataImportService,
                                    @Qualifier("knowledgeIndexExecutor") Executor indexExecutor) {
        this.jdbcTemplate = jdbcTemplate;
        this.scenicDataImportService = scenicDataImportService;
        this.indexExecutor = indexExecutor;
    }

    /** 保留本地检索/基准测试使用的轻量构造器，不创建后台线程。 */
    public KnowledgeDocumentService(JdbcTemplate jdbcTemplate,
                                    ScenicDataImportService scenicDataImportService) {
        this(jdbcTemplate, scenicDataImportService, Runnable::run);
    }

    private final RowMapper<KnowledgeDocument> rowMapper = (rs, rowNum) -> {
        KnowledgeDocument d = new KnowledgeDocument();
        d.setId(rs.getString("id"));
        d.setTitle(rs.getString("title"));
        d.setCategory(rs.getString("category"));
        d.setContent(rs.getString("content"));
        d.setTags(rs.getString("tags"));
        d.setFileUrl(rs.getString("file_url"));
        d.setFileMd5(rs.getString("file_md5"));
        d.setSourceName(rs.getString("source_name"));
        d.setStatus(rs.getString("status"));
        d.setVectorStatus(rs.getString("vector_status"));
        d.setChunkCount(rs.getInt("chunk_count"));
        d.setCreatedBy(rs.getString("created_by"));
        String ca = rs.getString("created_at");
        if (ca != null) d.setCreatedAt(LocalDateTime.parse(ca, DT_FMT));
        String ua = rs.getString("updated_at");
        if (ua != null) d.setUpdatedAt(LocalDateTime.parse(ua, DT_FMT));
        return d;
    };

    /**
     * 分页查询文档列表
     */
    public Map<String, Object> listDocuments(String category, String keyword, int page, int size) {
        // 兼容早期数据 status 为空的情况；SQL 中 NULL != 'archived' 不会返回 true。
        StringBuilder sql = new StringBuilder("SELECT * FROM kb_document WHERE (status IS NULL OR status <> 'archived')");
        List<Object> params = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            params.add(category);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (title LIKE ? OR content LIKE ?)");
            params.add("%" + keyword + "%");
            params.add("%" + keyword + "%");
        }

        // 计数
        String countSql = sql.toString().replaceFirst("SELECT \\*", "SELECT COUNT(*)");
        Integer total = jdbcTemplate.queryForObject(countSql, Integer.class, params.toArray());

        // 分页
        sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?");
        params.add(size);
        params.add((page - 1) * size);

        List<KnowledgeDocument> items = jdbcTemplate.query(sql.toString(), rowMapper, params.toArray());

        // 构造返回（content 只返回前 100 字符摘要）
        List<Map<String, Object>> records = new ArrayList<>();
        for (KnowledgeDocument d : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("title", d.getTitle());
            m.put("category", d.getCategory());
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
        result.put("total", total != null ? total : 0);
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
        int safeLimit = Math.min(20, Math.max(1, limit));
        List<String> terms = retrievalTerms(query).stream().limit(12).toList();
        var arguments = new ArrayList<Object>();
        int excerptChars = Math.max(256, Math.min(16384, retrievalExcerptChars));
        String needle = terms.isEmpty() ? "" : terms.get(0);
        arguments.add(needle); arguments.add(excerptChars / 4);
        arguments.add(needle); arguments.add(excerptChars / 4); arguments.add(excerptChars);
        var filters = new ArrayList<String>();
        var relevance = new ArrayList<String>();
        var relevanceArguments = new ArrayList<Object>();
        for (String term : terms) {
            filters.add("(LOWER(title) LIKE ? ESCAPE '!' OR LOWER(tags) LIKE ? ESCAPE '!' OR LOWER(content) LIKE ? ESCAPE '!')");
            String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            arguments.add(pattern); arguments.add(pattern); arguments.add(pattern);
            relevance.add("CASE WHEN LOWER(title) LIKE ? ESCAPE '!' THEN ? ELSE 0 END"
                    + " + CASE WHEN LOWER(tags) LIKE ? ESCAPE '!' THEN ? ELSE 0 END"
                    + " + CASE WHEN LOWER(content) LIKE ? ESCAPE '!' THEN ? ELSE 0 END");
            relevanceArguments.add(pattern); relevanceArguments.add(term.length() * 5);
            relevanceArguments.add(pattern); relevanceArguments.add(term.length() * 3);
            relevanceArguments.add(pattern); relevanceArguments.add(term.length());
        }
        arguments.addAll(relevanceArguments);
        arguments.add(Math.max(safeLimit, Math.min(2000, retrievalMaxCandidates)));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, title, SUBSTR(content, CASE WHEN " + retrievalPositionFunction() + "(LOWER(content), ?) > ? THEN "
                        + retrievalPositionFunction() + "(LOWER(content), ?) - ? ELSE 1 END, ?) AS content, tags, source_name FROM kb_document "
                        + "WHERE (status IS NULL OR status <> 'archived') "
                        + (filters.isEmpty() ? "" : "AND (" + String.join(" OR ", filters) + ") ")
                        + "ORDER BY " + (relevance.isEmpty() ? "" : "(" + String.join(" + ", relevance) + ") DESC, ")
                        + "updated_at DESC LIMIT ?", arguments.toArray());
        List<Map<String, Object>> ranked = new ArrayList<>();
        int windowBudget = Math.max(20, safeLimit * 4);
        for (Map<String, Object> row : rows) {
            String title = String.valueOf(row.getOrDefault("title", ""));
            String content = String.valueOf(row.getOrDefault("content", ""));
            if (windowBudget-- > 0)
                content = relevantExcerpt(String.valueOf(row.get("id")), terms, content, excerptChars);
            String tags = String.valueOf(row.getOrDefault("tags", ""));
            String searchable = (title + " " + content + " " + tags).toLowerCase(Locale.ROOT);
            int score = RagQueryExpander.score(query, title, tags, content);
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
                Integer relative = jdbcTemplate.queryForObject("SELECT " + retrievalPositionFunction()
                        + "(LOWER(SUBSTR(content, ?)), ?) FROM kb_document WHERE id = ?", Integer.class, offset, term, id);
                if (relative == null || relative <= 0) break;
                int position = offset + relative - 1;
                String window = jdbcTemplate.queryForObject("SELECT SUBSTR(content, ?, ?) FROM kb_document WHERE id = ?",
                        String.class, Math.max(1, position - size / 4), size, id);
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
        terms.removeAll(Set.of("重庆", "旅行", "行程", "景点", "希望", "需要", "可以"));
        return new ArrayList<>(terms);
    }

    /**
     * 获取所有分类及其文档数
     */
    public List<Map<String, Object>> getCategories() {
        String sql = "SELECT category, COUNT(*) as cnt FROM kb_document WHERE (status IS NULL OR status <> 'archived') GROUP BY category";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", rs.getString("category"));
            m.put("label", categoryLabel(rs.getString("category")));
            m.put("count", rs.getInt("cnt"));
            return m;
        });
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
        String sql = "SELECT * FROM kb_document WHERE id = ?";
        List<KnowledgeDocument> list = jdbcTemplate.query(sql, rowMapper, docId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 创建文档（支持文本或文件上传）
     */
    public KnowledgeDocument createDocument(String title, String category, String content,
                                             String tags, MultipartFile file, String userId) throws IOException {
        // 检查同名
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_document WHERE title = ? AND (status IS NULL OR status <> 'archived')",
                Integer.class, title);
        if (count != null && count > 0) {
            throw new IllegalArgumentException("已存在同名文档: " + title);
        }

        String id = UUID.randomUUID().toString();
        String fileUrl = "";
        String fileMd5 = "";

        // 文件上传处理
        if (file != null && !file.isEmpty()) {
            if (runtimePolicy != null) runtimePolicy.checkUploadSize(file.getSize());
            fileMd5 = md5(file.getBytes());
            // MD5 去重
            Integer dupCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kb_document WHERE file_md5 = ? AND (status IS NULL OR status <> 'archived')",
                    Integer.class, fileMd5);
            if (dupCount != null && dupCount > 0) {
                throw new IllegalArgumentException("文件已存在（相同 MD5）");
            }
            fileUrl = saveFile(file);
        }

        // 文件文档使用保存后的唯一文件名，纯文本使用稳定的文档 ID，二者都可作为 Qdrant source。
        String sourceName = fileUrl.isEmpty() ? "text-" + id : new File(fileUrl).getName();

        String now = LocalDateTime.now().format(DT_FMT);
        String sql = "INSERT INTO kb_document (id, title, category, content, tags, file_url, file_md5, source_name, status, vector_status, chunk_count, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'active', 'pending', 0, ?, ?, ?)";
        jdbcTemplate.update(sql, id, title, category, content != null ? content : "", tags != null ? tags : "[]", fileUrl, fileMd5, sourceName, userId != null ? userId : "", now, now);

        log.info("[Knowledge] 创建文档 id={} title={}", id, title);

        // 异步触发向量化；纯文本直接用编辑内容生成碎片，文件则按原文件解析。
        if (syncEnabled() && !fileUrl.isEmpty()) {
            triggerSyncAsync(id, fileUrl);
        } else if (syncEnabled() && content != null && !content.isBlank()) {
            triggerTextSyncAsync(id, sourceName, content);
        }

        return getDocument(id);
    }

    /**
     * 更新文档元数据，并在正文变更时以管理员保存的内容重新生成向量。
     * 文件文档的“刷新”由 triggerSync 单独处理，始终以原文件为准。
     */
    public KnowledgeDocument updateDocument(String docId, String title, String category,
                                             String content, String tags) {
        synchronized (documentLock(docId)) { return updateLocked(docId, title, category, content, tags); }
    }
    private KnowledgeDocument updateLocked(String docId, String title, String category, String content, String tags) {
        KnowledgeDocument existing = getDocument(docId);
        requireActive(existing, docId);
        boolean contentChanged = content != null && !Objects.equals(content, existing.getContent());
        String sourceName = resolveSourceName(existing);

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE kb_document SET updated_at = ?");
        params.add(LocalDateTime.now().format(DT_FMT));

        if (title != null) { sql.append(", title = ?"); params.add(title); }
        if (category != null) { sql.append(", category = ?"); params.add(category); }
        if (content != null) { sql.append(", content = ?"); params.add(content); }
        if (tags != null) { sql.append(", tags = ?"); params.add(tags); }
        if (contentChanged) {
            sql.append(", source_name = ?, vector_status = 'pending', index_revision = index_revision + 1");
            params.add(sourceName);
        }
        sql.append(" WHERE id = ?");
        params.add(docId);

        jdbcTemplate.update(sql.toString(), params.toArray());
        log.info("[Knowledge] 更新文档 id={}", docId);

        // 只有正文实际改变时才覆盖 Qdrant。标题、分类、标签变化无需重复调用 embedding。
        if (contentChanged && content != null && !content.isBlank() && syncEnabled()) {
            triggerTextSyncAsync(docId, sourceName, content);
        } else if (contentChanged && content != null && content.isBlank()) {
            // 管理员明确清空正文时，向量也必须同步清空，不能让待同步状态永久卡住。
            scenicDataImportService.deleteDocumentVectors(sourceName);
            jdbcTemplate.update("UPDATE kb_document SET source_name = ?, vector_status = 'synced', chunk_count = 0 WHERE id = ?", sourceName, docId);
        }

        return getDocument(docId);
    }

    /**
     * 删除文档：先删除 Qdrant 中同 source 的碎片，再归档元数据并清理受控上传目录中的源文件。
     */
    public void deleteDocument(String docId) {
        synchronized (documentLock(docId)) { deleteLocked(docId); }
    }
    private void deleteLocked(String docId) {
        KnowledgeDocument existing = getDocument(docId);
        requireActive(existing, docId);
        scenicDataImportService.deleteDocumentVectors(resolveSourceName(existing));
        // Preserve a canonical persistent source tombstone, including old file records
        // whose source_name was empty. Historical backfill cannot restore this source.
        String sql = "UPDATE kb_document SET status = 'archived', source_name = ?, index_revision = index_revision + 1, updated_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, resolveSourceName(existing), LocalDateTime.now().format(DT_FMT), docId);
        deleteStoredFile(existing.getFileUrl());
        log.info("[Knowledge] 软删除文档 id={}", docId);
    }

    /**
     * 触发向量化同步
     */
    public Map<String, Object> triggerSync(String docId) {
        synchronized (documentLock(docId)) { return triggerSyncLocked(docId); }
    }
    private Map<String, Object> triggerSyncLocked(String docId) {
        if (runtimePolicy != null) runtimePolicy.requireKnowledgeSyncEnabled();
        KnowledgeDocument doc = getDocument(docId);
        requireActive(doc, docId);

        // 更新状态为 syncing
        jdbcTemplate.update("UPDATE kb_document SET vector_status = 'syncing', index_revision = index_revision + 1 WHERE id = ?", docId);

        try {
            String sourceName = resolveSourceName(doc);
            int chunks;
            if (doc.getFileUrl() != null && !doc.getFileUrl().isEmpty()) {
                scenicDataImportService.reindexDocument(new File(doc.getFileUrl()));
                chunks = scenicDataImportService.countChunksBySource(sourceName);
            } else {
                if (!isRefreshable(doc)) {
                    throw new IllegalArgumentException("该历史文档没有原始文件，不能刷新；可编辑正文后重新向量化");
                }
                chunks = scenicDataImportService.reindexTextDocument(sourceName, doc.getContent());
            }
            jdbcTemplate.update("UPDATE kb_document SET source_name = ?, vector_status = 'synced', chunk_count = ? WHERE id = ?", sourceName, chunks, docId);
            log.info("[Knowledge] 向量化成功 id={}", docId);
        } catch (Exception e) {
            jdbcTemplate.update("UPDATE kb_document SET vector_status = 'failed' WHERE id = ?", docId);
            log.error("[Knowledge] 向量化失败 id={}: {}", docId, e.getMessage());
            throw new RuntimeException("向量化失败: " + e.getMessage());
        }

        KnowledgeDocument updated = getDocument(docId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("docId", updated.getId());
        result.put("vectorStatus", updated.getVectorStatus());
        result.put("chunkCount", updated.getChunkCount());
        return result;
    }

    /** 异步按原始文件刷新向量。 */
    private void triggerSyncAsync(String docId, String fileUrl) {
        File sourceFile = new File(fileUrl);
        submitIndexTask(docId, "文件", () -> {
            String sourceName = sourceFile.getName();
            scenicDataImportService.reindexDocument(sourceFile);
            int chunks = scenicDataImportService.countChunksBySource(sourceName);
            markVectorSynced(docId, sourceName, chunks);
        });
    }

    /** 异步按管理员编辑后的正文刷新向量。 */
    private void triggerTextSyncAsync(String docId, String sourceName, String content) {
        submitIndexTask(docId, "文本", () -> {
            int chunks = scenicDataImportService.reindexTextDocument(sourceName, content);
            markVectorSynced(docId, sourceName, chunks);
        });
    }

    /**
     * 提交后台索引任务并统一处理过载、异常和状态回写。
     * 不使用 Thread.sleep：JdbcTemplate.update 已完成后，任务可直接进入受控队列。
     */
    private void submitIndexTask(String docId, String sourceType, Runnable task) {
        long revision = indexRevision(docId);
        try {
            indexExecutor.execute(() -> {
                synchronized (documentLock(docId)) {
                    if (!syncEnabled() || !currentIndex(docId, revision)) return;
                    try { activeIndexRevision.set(revision); task.run(); }
                    catch (Exception e) {
                        markVectorFailed(docId, revision);
                        log.error("[Knowledge] 异步{}向量化失败 id={}: {}", sourceType, docId, e.getMessage());
                    } finally { activeIndexRevision.remove(); }
                }
            });
        } catch (RejectedExecutionException e) {
            markVectorFailed(docId, revision);
            log.warn("[Knowledge] 索引队列已满 id={}", docId);
        }
    }

    private void markVectorSynced(String docId, String sourceName, int chunks) {
        long revision = activeIndexRevision.get() == null ? indexRevision(docId) : activeIndexRevision.get();
        jdbcTemplate.update("UPDATE kb_document SET source_name = ?, vector_status = 'synced', chunk_count = ? WHERE id = ? AND index_revision = ? AND (status IS NULL OR status <> 'archived')", sourceName, chunks, docId, revision);
    }

    private void markVectorFailed(String docId, long revision) {
        jdbcTemplate.update("UPDATE kb_document SET vector_status = 'failed' WHERE id = ? AND index_revision = ? AND (status IS NULL OR status <> 'archived')", docId, revision);
    }

    /**
     * 将已有 Qdrant source 回填为 kb_document，解决旧版本“碎片存在、文档列表为空”的断层。
     * 回填不伪造原始文件，后续编辑会以回填的文本摘要重建向量。
     */
    public Map<String, Object> backfillDocumentsFromVectors() {
        int created = 0;
        int updated = 0;
        int skipped = 0;
        List<Map<String, Object>> summaries = scenicDataImportService.listSourceSummaries();
        Set<String> archivedSources = new HashSet<>(jdbcTemplate.query(
                "SELECT source_name, file_url, title FROM kb_document WHERE status = 'archived'", (rs, row) -> {
                    String source = rs.getString("source_name"), file = rs.getString("file_url"), title = rs.getString("title");
                    if (source != null && !source.isBlank()) return source;
                    return file != null && !file.isBlank() ? new File(file).getName() : title;
                }));
        String now = LocalDateTime.now().format(DT_FMT);
        for (Map<String, Object> summary : summaries) {
            String sourceName = String.valueOf(summary.getOrDefault("sourceName", ""));
            if (sourceName.isBlank()) continue;
            int chunkCount = ((Number) summary.getOrDefault("chunkCount", 0)).intValue();
            String content = String.valueOf(summary.getOrDefault("content", ""));
            // Archived rows are persistent deletion tombstones. This check also
            // covers legacy file records whose source_name was never populated.
            if (archivedSources.contains(sourceName)) { skipped++; continue; }
            Integer existing = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kb_document WHERE (source_name = ? OR ((source_name IS NULL OR source_name = '') AND title = ?)) AND (status IS NULL OR status <> 'archived')",
                    Integer.class, sourceName, sourceName);
            if (existing != null && existing > 0) {
                int changed = jdbcTemplate.update("UPDATE kb_document SET source_name = ?, vector_status = 'synced', chunk_count = ?, updated_at = ? WHERE (source_name = ? OR ((source_name IS NULL OR source_name = '') AND title = ?)) AND (status IS NULL OR status <> 'archived')",
                        sourceName, chunkCount, now, sourceName, sourceName);
                if (changed > 0) updated++; else skipped++;
                continue;
            }

            // Check all states again within the INSERT statement. A deletion
            // between the prior lookup and this write must not create a fresh UUID.
            int inserted = jdbcTemplate.update("INSERT INTO kb_document (id, title, category, content, tags, file_url, file_md5, source_name, status, vector_status, chunk_count, created_by, created_at, updated_at) SELECT ?, ?, 'other', ?, '[]', '', '', ?, 'active', 'synced', ?, 'system-backfill', ?, ? WHERE NOT EXISTS (SELECT 1 FROM kb_document WHERE source_name = ? OR ((source_name IS NULL OR source_name = '') AND title = ?))",
                    UUID.randomUUID().toString(), sourceName, content, sourceName, chunkCount, now, now, sourceName, sourceName);
            if (inserted > 0) created++; else skipped++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sources", summaries.size());
        result.put("created", created);
        result.put("updated", updated);
        result.put("skipped", skipped);
        return result;
    }

    /** 将实体转换为前端可直接使用的 camelCase 响应。 */
    public Map<String, Object> toResponse(KnowledgeDocument document, boolean includeContent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", document.getId());
        data.put("title", document.getTitle());
        data.put("category", document.getCategory());
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
            return new File(document.getFileUrl()).getName();
        }
        return document.getTitle() != null && !document.getTitle().isBlank()
                ? document.getTitle() : "text-" + document.getId();
    }

    /** 回填文档没有可追溯原文件，禁止“刷新”覆盖其现有向量。 */
    private boolean isRefreshable(KnowledgeDocument document) {
        return document.getFileUrl() != null && !document.getFileUrl().isBlank()
                || !"system-backfill".equals(document.getCreatedBy());
    }

    /** 仅删除应用上传目录中的文件，避免错误处理数据库中任意路径。 */
    private void deleteStoredFile(String fileUrl) {
        if (fileUrl == null || fileUrl.isBlank()) return;
        try {
            File uploadRoot = new File(uploadDirectory).getCanonicalFile();
            File storedFile = new File(fileUrl).getCanonicalFile();
            String rootPath = uploadRoot.getPath() + File.separator;
            if (!storedFile.getPath().startsWith(rootPath)) {
                log.warn("[Knowledge] 跳过非上传目录文件删除: {}", storedFile);
                return;
            }
            if (storedFile.exists() && !storedFile.delete()) {
                log.warn("[Knowledge] 源文件删除失败: {}", storedFile);
            }
        } catch (Exception e) {
            log.warn("[Knowledge] 清理源文件失败: {}", e.getMessage());
        }
    }

    private String saveFile(MultipartFile file) throws IOException {
        String uploadDir = uploadDirectory.endsWith(File.separator)
                ? uploadDirectory
                : uploadDirectory + File.separator;
        File dir = new File(uploadDir);
        if (!dir.exists()) dir.mkdirs();

        String originalName = file.getOriginalFilename();
        String ext = originalName != null && originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.')) : "";
        String filename = UUID.randomUUID().toString() + ext;
        File dest = new File(uploadDir + filename);
        file.transferTo(dest);
        return new File(uploadDir, filename).getPath();
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
