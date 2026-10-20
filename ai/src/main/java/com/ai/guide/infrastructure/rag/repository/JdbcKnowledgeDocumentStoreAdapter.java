package com.ai.guide.infrastructure.rag.repository;

import com.ai.guide.domain.rag.gateway.KnowledgeDocumentStorePort;
import com.ai.guide.domain.rag.gateway.KnowledgeIndexVisibilityPort;
import com.ai.guide.domain.rag.model.KnowledgeDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** JDBC implementation for the knowledge-document store port. */
@Repository
public class JdbcKnowledgeDocumentStoreAdapter implements KnowledgeDocumentStorePort, KnowledgeIndexVisibilityPort {
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;
    private volatile String positionFunction;

    public JdbcKnowledgeDocumentStoreAdapter(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public Map<String, IndexedSource> visibleSources(Set<String> sourceNames) {
        if (sourceNames == null || sourceNames.isEmpty()) return Map.of();
        var names = sourceNames.stream().filter(name -> name != null && !name.isBlank()).toList();
        if (names.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(names.size(), "?"));
        String sql = "SELECT source_name, scope, city, vector_status, index_revision FROM kb_document WHERE source_name IN ("
                + placeholders + ") AND (status IS NULL OR status <> 'archived')";
        Map<String, Integer> counts = new HashMap<>();
        Map<String, IndexedSource> visible = new HashMap<>();
        jdbc.query(sql, rs -> {
            String source = rs.getString("source_name");
            counts.merge(source, 1, Integer::sum);
            if ("synced".equals(rs.getString("vector_status"))) {
                try {
                    visible.put(source, new IndexedSource(
                            new com.ai.guide.domain.rag.model.KnowledgeScope(rs.getString("scope"), rs.getString("city")),
                            rs.getLong("index_revision")));
                } catch (IllegalArgumentException invalidScope) {
                    visible.remove(source);
                }
            }
        }, names.toArray());
        visible.entrySet().removeIf(entry -> counts.getOrDefault(entry.getKey(), 0) != 1);
        return Map.copyOf(visible);
    }

    @Override public <T> T withDocumentLock(String id, java.util.function.Supplier<T> operation) {
        var template = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        // Also safe for a direct executor invoked by an afterCommit callback: do not
        // accidentally join its already-committed, still thread-bound transaction.
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(transaction -> {
                    // The same row lock fences publication, updates and archival across JVMs.
                    jdbc.update("UPDATE kb_document SET index_revision = index_revision WHERE id = ?", id);
                    return operation.get();
                });
    }

    @Override public void afterCommit(Runnable operation) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            operation.run();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        // The committed transaction's connection is still bound until cleanup.
                        // A direct executor must not start reads on that connection and then
                        // suspend its SQLite read lock while publishing on a new connection.
                        var detached = new org.springframework.transaction.support.TransactionTemplate(
                                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
                        detached.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
                        detached.execute(status -> { operation.run(); return null; });
                    }
                });
    }

    private final RowMapper<KnowledgeDocument> rowMapper = (rs, row) -> {
        KnowledgeDocument d = new KnowledgeDocument();
        d.setId(rs.getString("id")); d.setTitle(rs.getString("title")); d.setCategory(rs.getString("category"));
        d.setScope(rs.getString("scope")); d.setCity(rs.getString("city"));
        d.setContent(rs.getString("content")); d.setTags(rs.getString("tags")); d.setFileUrl(rs.getString("file_url"));
        d.setFileMd5(rs.getString("file_md5")); d.setSourceName(rs.getString("source_name")); d.setSourceUrl(rs.getString("source_url")); d.setStatus(rs.getString("status"));
        d.setVectorStatus(rs.getString("vector_status")); d.setIndexRequestMode(rs.getString("index_request_mode")); d.setChunkCount(rs.getInt("chunk_count")); d.setCreatedBy(rs.getString("created_by"));
        String created = rs.getString("created_at"), updated = rs.getString("updated_at");
        if (created != null) d.setCreatedAt(LocalDateTime.parse(created, DT_FMT));
        if (updated != null) d.setUpdatedAt(LocalDateTime.parse(updated, DT_FMT));
        return d;
    };

    @Override public Page list(String category, String keyword, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM kb_document WHERE (status IS NULL OR status <> 'archived')");
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank()) { sql.append(" AND category = ?"); args.add(category); }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (title LIKE ? OR content LIKE ?)"); args.add("%" + keyword + "%"); args.add("%" + keyword + "%");
        }
        String countSql = sql.toString().replaceFirst("SELECT \\*", "SELECT COUNT(*)");
        Integer total = jdbc.queryForObject(countSql, Integer.class, args.toArray());
        sql.append(" ORDER BY updated_at DESC LIMIT ? OFFSET ?"); args.add(size); args.add((page - 1) * size);
        return new Page(total == null ? 0 : total, jdbc.query(sql.toString(), rowMapper, args.toArray()));
    }

    @Override public List<Map<String, Object>> searchCandidates(List<String> terms, String needle, int excerptChars, int limit) {
        return searchCandidates(terms, needle, excerptChars, limit, com.ai.guide.domain.rag.model.KnowledgeScope.global());
    }
    @Override public List<Map<String, Object>> searchCandidates(List<String> terms, String needle, int excerptChars, int limit, com.ai.guide.domain.rag.model.KnowledgeScope scope) {
        String pos = positionFunction();
        var args = new ArrayList<Object>();
        args.add(needle); args.add(excerptChars / 4); args.add(needle); args.add(excerptChars / 4); args.add(excerptChars);
        var filters = new ArrayList<String>(); var relevance = new ArrayList<String>(); var relevanceArgs = new ArrayList<Object>();
        for (String term : terms) {
            filters.add("(LOWER(title) LIKE ? ESCAPE '!' OR LOWER(tags) LIKE ? ESCAPE '!' OR LOWER(content) LIKE ? ESCAPE '!')");
            String pattern = "%" + term.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            args.add(pattern); args.add(pattern); args.add(pattern);
            relevance.add("CASE WHEN LOWER(title) LIKE ? ESCAPE '!' THEN ? ELSE 0 END"
                    + " + CASE WHEN LOWER(tags) LIKE ? ESCAPE '!' THEN ? ELSE 0 END"
                    + " + CASE WHEN LOWER(content) LIKE ? ESCAPE '!' THEN ? ELSE 0 END");
            relevanceArgs.add(pattern); relevanceArgs.add(term.length() * 5);
            relevanceArgs.add(pattern); relevanceArgs.add(term.length() * 3);
            relevanceArgs.add(pattern); relevanceArgs.add(term.length());
        }
        String applicability = " AND (scope = 'GLOBAL'";
        if ("CITY".equals(scope.scope())) { applicability += " OR (scope = 'CITY' AND city = ?)"; args.add(scope.city()); }
        applicability += ") ";
        args.addAll(relevanceArgs); args.add(limit);
        return jdbc.queryForList("SELECT id, title, SUBSTR(content, CASE WHEN " + pos + "(LOWER(content), ?) > ? THEN "
                + pos + "(LOWER(content), ?) - ? ELSE 1 END, ?) AS content, tags, source_name, source_url, scope, city FROM kb_document "
                + "WHERE (status IS NULL OR status <> 'archived') " + (filters.isEmpty() ? "" : "AND (" + String.join(" OR ", filters) + ") ")
                + applicability + "ORDER BY " + (relevance.isEmpty() ? "" : "(" + String.join(" + ", relevance) + ") DESC, ")
                + "updated_at DESC LIMIT ?", args.toArray());
    }

    @Override public Integer relativeMatch(String id, int offset, String term) {
        return jdbc.queryForObject("SELECT " + positionFunction() + "(LOWER(SUBSTR(content, ?)), ?) FROM kb_document WHERE id = ?", Integer.class, offset, term, id);
    }
    @Override public String contentWindow(String id, int start, int length) {
        return jdbc.queryForObject("SELECT SUBSTR(content, ?, ?) FROM kb_document WHERE id = ?", String.class, start, length, id);
    }
    @Override public List<CategoryCount> categories() {
        return jdbc.query("SELECT category, COUNT(*) as cnt FROM kb_document WHERE (status IS NULL OR status <> 'archived') GROUP BY category",
                (rs, row) -> new CategoryCount(rs.getString("category"), rs.getInt("cnt")));
    }
    @Override public KnowledgeDocument findById(String id) {
        List<KnowledgeDocument> rows = jdbc.query("SELECT * FROM kb_document WHERE id = ?", rowMapper, id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    @Override public boolean activeTitleExists(String title) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE title = ? AND (status IS NULL OR status <> 'archived')", Integer.class, title);
        return count != null && count > 0;
    }
    @Override public boolean activeFileHashExists(String hash) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE file_md5 = ? AND (status IS NULL OR status <> 'archived')", Integer.class, hash);
        return count != null && count > 0;
    }
    @Override public void insert(KnowledgeDocument d) {
        jdbc.update("INSERT INTO kb_document (id,title,category,content,tags,file_url,file_md5,source_name,scope,city,status,vector_status,chunk_count,created_by,created_at,updated_at,index_request_mode) VALUES (?,?,?,?,?,?,?,?,?,?,'active','pending',0,?,?,?,?)",
                d.getId(), d.getTitle(), d.getCategory(), d.getContent(), d.getTags(), d.getFileUrl(), d.getFileMd5(), d.getSourceName(), d.getScope(), d.getCity(), d.getCreatedBy(), d.getCreatedAt().format(DT_FMT), d.getUpdatedAt().format(DT_FMT), d.getIndexRequestMode() == null ? "" : d.getIndexRequestMode());
    }
    @Override public int update(String id, String title, String category, String content, String tags, String sourceName, boolean contentChanged, String updatedAt) {
        return update(id, title, category, content, tags, sourceName, contentChanged, updatedAt, null);
    }
    @Override public int update(String id, String title, String category, String content, String tags, String sourceName, boolean contentChanged, String updatedAt, com.ai.guide.domain.rag.model.KnowledgeScope scope) {
        var sql = new StringBuilder("UPDATE kb_document SET updated_at = ?"); var args = new ArrayList<Object>(); args.add(updatedAt);
        if (scope != null) { sql.append(", scope = ?, city = ?"); args.add(scope.scope()); args.add(scope.city()); }
        if (title != null) { sql.append(", title = ?"); args.add(title); }
        if (category != null) { sql.append(", category = ?"); args.add(category); }
        if (content != null) { sql.append(", content = ?"); args.add(content); }
        if (tags != null) { sql.append(", tags = ?"); args.add(tags); }
        sql.append(", source_name = ?"); args.add(sourceName);
        if (contentChanged) sql.append(", source_url = '', vector_status = 'pending', index_revision = index_revision + 1");
        sql.append(" WHERE id = ?"); args.add(id);
        return jdbc.update(sql.toString(), args.toArray());
    }
    @Override public boolean activeTitleExists(String title, com.ai.guide.domain.rag.model.KnowledgeScope scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE title = ? AND scope = ? AND city = ? AND (status IS NULL OR status <> 'archived')", Integer.class, title, scope.scope(), scope.city()) > 0;
    }
    @Override public boolean activeFileHashExists(String hash, com.ai.guide.domain.rag.model.KnowledgeScope scope) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE file_md5 = ? AND scope = ? AND city = ? AND (status IS NULL OR status <> 'archived')", Integer.class, hash, scope.scope(), scope.city()) > 0;
    }
    @Override public int updateVectorState(String id, String sourceName, String status, Integer chunks) {
        return jdbc.update("UPDATE kb_document SET source_name = ?, vector_status = ?, chunk_count = ? WHERE id = ?", sourceName, status, chunks, id);
    }
    @Override public int archive(String id, String sourceName, String updatedAt) {
        return jdbc.update("UPDATE kb_document SET status = 'archived', source_name = ?, index_revision = index_revision + 1, updated_at = ?, vector_status = 'pending', index_request_mode = 'DELETE', index_attempts = 0, index_next_attempt_at = 0, index_lease_token = '', index_lease_until = 0 WHERE id = ? AND (status IS NULL OR status <> 'archived')", sourceName, updatedAt, id);
    }
    @Override public int requestIndex(String id, String mode, boolean bumpRevision) {
        if (!Set.of("TEXT", "FILE", "DELETE").contains(mode)) throw new IllegalArgumentException("Invalid index mode");
        return jdbc.update("UPDATE kb_document SET vector_status = 'pending', index_request_mode = ?, index_attempts = 0, index_next_attempt_at = 0, index_lease_token = '', index_lease_until = 0"
                        + (bumpRevision ? ", index_revision = index_revision + 1" : "")
                        + " WHERE id = ? AND (status IS NULL OR status <> 'archived')", mode, id);
    }
    @Override public IndexClaim claimIndex(String id, long now, long leaseMillis) {
        String token = UUID.randomUUID().toString();
        int updated = jdbc.update("UPDATE kb_document SET vector_status = 'syncing', index_attempts = index_attempts + 1, index_lease_token = ?, index_lease_until = ? "
                + "WHERE id = ? AND index_request_mode <> '' AND index_next_attempt_at <= ? "
                + "AND (vector_status IN ('pending','failed') OR (vector_status = 'syncing' AND index_lease_until <= ?)) "
                + "AND (((status IS NULL OR status <> 'archived') AND index_request_mode IN ('TEXT','FILE','DELETE')) OR (status = 'archived' AND index_request_mode = 'DELETE'))",
                token, now + leaseMillis, id, now, now);
        if (updated != 1) return null;
        KnowledgeDocument document = findById(id);
        Map<String, Object> metadata = jdbc.queryForMap("SELECT index_revision,index_request_mode,index_attempts FROM kb_document WHERE id = ?", id);
        return new IndexClaim(document, ((Number) metadata.get("index_revision")).longValue(), token,
                String.valueOf(metadata.get("index_request_mode")), ((Number) metadata.get("index_attempts")).intValue());
    }
    @Override public boolean ownsIndexClaim(String id, long revision, String token, long now) {
        // An expired lease remains owned until another worker actually claims it.
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE id = ? AND index_revision = ? AND index_lease_token = ? AND vector_status = 'syncing' AND index_request_mode <> ''", Integer.class, id, revision, token);
        return count != null && count == 1;
    }
    @Override public int finishIndexSuccess(String id, long revision, String token, String sourceName, int chunks) {
        // Retain the last source mode after completion: a later scope-only change must
        // reindex edited TEXT, not silently revert it to the original uploaded FILE.
        return jdbc.update("UPDATE kb_document SET source_name = ?, vector_status = 'synced', chunk_count = ?, index_attempts = 0, index_next_attempt_at = 0, index_lease_token = '', index_lease_until = 0 WHERE id = ? AND index_revision = ? AND index_lease_token = ? AND vector_status = 'syncing'", sourceName, chunks, id, revision, token);
    }
    @Override public int finishIndexFailure(String id, long revision, String token, long nextAttempt) {
        return jdbc.update("UPDATE kb_document SET vector_status = 'failed', index_next_attempt_at = ?, index_lease_token = '', index_lease_until = 0 WHERE id = ? AND index_revision = ? AND index_lease_token = ? AND vector_status = 'syncing'", nextAttempt, id, revision, token);
    }
    @Override public List<String> dueIndexIds(long now, int limit, boolean includeEmbedding) {
        return jdbc.queryForList("SELECT id FROM kb_document WHERE index_request_mode <> '' AND index_next_attempt_at <= ? "
                + (includeEmbedding ? "" : "AND index_request_mode = 'DELETE' ")
                + "AND (vector_status IN ('pending','failed') OR (vector_status = 'syncing' AND index_lease_until <= ?)) "
                + "AND (((status IS NULL OR status <> 'archived') AND index_request_mode IN ('TEXT','FILE','DELETE')) OR (status = 'archived' AND index_request_mode = 'DELETE')) "
                + "ORDER BY index_next_attempt_at,id LIMIT ?", String.class, now, now, Math.max(1, Math.min(limit, 100)));
    }
    @Override public int markSyncing(String id) {
        return jdbc.update("UPDATE kb_document SET vector_status = 'syncing', index_revision = index_revision + 1 WHERE id = ?", id);
    }
    @Override public int markSynced(String id, String sourceName, int chunks, Long revision) {
        if (revision == null) return jdbc.update("UPDATE kb_document SET source_name = ?, vector_status = 'synced', chunk_count = ? WHERE id = ?", sourceName, chunks, id);
        return jdbc.update("UPDATE kb_document SET source_name = ?, vector_status = 'synced', chunk_count = ? WHERE id = ? AND index_revision = ? AND (status IS NULL OR status <> 'archived')", sourceName, chunks, id, revision);
    }
    @Override public int markFailed(String id, Long revision) {
        if (revision == null) return jdbc.update("UPDATE kb_document SET vector_status = 'failed' WHERE id = ?", id);
        return jdbc.update("UPDATE kb_document SET vector_status = 'failed' WHERE id = ? AND index_revision = ? AND (status IS NULL OR status <> 'archived')", id, revision);
    }
    @Override public Long indexRevision(String id) { return jdbc.queryForObject("SELECT index_revision FROM kb_document WHERE id = ?", Long.class, id); }
    @Override public boolean isCurrentIndex(String id, long revision) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE id = ? AND index_revision = ? AND (status IS NULL OR status <> 'archived')", Integer.class, id, revision);
        return count != null && count == 1;
    }
    @Override public List<ArchivedSource> archivedSources() {
        return jdbc.query("SELECT source_name,file_url,title FROM kb_document WHERE status = 'archived'",
                (rs, row) -> new ArchivedSource(rs.getString("source_name"), rs.getString("file_url"), rs.getString("title")));
    }
    @Override public BackfillOutcome backfill(String sourceName, String content, int chunks, String now) {
        return backfill(sourceName, content, chunks, now, com.ai.guide.domain.rag.model.KnowledgeScope.unspecified());
    }
    @Override public BackfillOutcome backfill(String sourceName, String content, int chunks, String now, com.ai.guide.domain.rag.model.KnowledgeScope scope) {
        return backfill(sourceName, content, chunks, now, scope, 0L);
    }
    @Override public BackfillOutcome backfill(String sourceName, String content, int chunks, String now,
                                               com.ai.guide.domain.rag.model.KnowledgeScope scope, Long revision) {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(
                        Objects.requireNonNull(jdbc.getDataSource())));
        return transaction.execute(status -> {
            jdbc.update("INSERT INTO kb_backfill_source_lock (source_name) VALUES (?) ON CONFLICT(source_name) DO NOTHING", sourceName);
            jdbc.update("UPDATE kb_backfill_source_lock SET source_name = source_name WHERE source_name = ?", sourceName);
            return backfillLocked(sourceName, content, chunks, now, scope, revision);
        });
    }
    private BackfillOutcome backfillLocked(String sourceName, String content, int chunks, String now,
                                            com.ai.guide.domain.rag.model.KnowledgeScope scope, Long revision) {
        Integer exact = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE source_name = ? AND (status IS NULL OR status <> 'archived')", Integer.class, sourceName);
        Integer legacy = jdbc.queryForObject("SELECT COUNT(*) FROM kb_document WHERE (source_name IS NULL OR source_name = '') AND title = ? AND (status IS NULL OR status <> 'archived')", Integer.class, sourceName);
        if ((exact != null && exact > 1) || ((exact == null || exact == 0) && legacy != null && legacy > 1))
            return BackfillOutcome.SKIPPED;
        if ((exact != null && exact == 1) || (legacy != null && legacy == 1)) {
            if (revision == null) return BackfillOutcome.SKIPPED;
            String predicate = exact != null && exact == 1 ? "source_name = ?" : "(source_name IS NULL OR source_name = '') AND title = ?";
            int changed = jdbc.update("UPDATE kb_document SET source_name = ?, vector_status = CASE WHEN vector_status IN ('pending','syncing','failed') THEN vector_status ELSE 'synced' END, chunk_count = CASE WHEN vector_status IN ('pending','syncing','failed') THEN chunk_count ELSE ? END, updated_at = ? WHERE "
                    + predicate + " AND (status IS NULL OR status <> 'archived') AND index_revision = ?",
                    sourceName, chunks, now, sourceName, revision);
            return changed > 0 ? BackfillOutcome.UPDATED : BackfillOutcome.SKIPPED;
        }
        int inserted = jdbc.update("INSERT INTO kb_document (id,title,category,content,tags,file_url,file_md5,source_name,scope,city,status,vector_status,index_revision,chunk_count,created_by,created_at,updated_at) SELECT ?, ?, 'other', ?, '[]', '', '', ?, ?, ?, 'active', ?, ?, ?, 'system-backfill', ?, ? WHERE NOT EXISTS (SELECT 1 FROM kb_document WHERE source_name = ? OR ((source_name IS NULL OR source_name = '') AND title = ?))",
                UUID.randomUUID().toString(), sourceName, content, sourceName, scope.scope(), scope.city(),
                revision == null ? "failed" : "synced", revision == null ? 0L : revision, chunks, now, now, sourceName, sourceName);
        return inserted > 0 ? BackfillOutcome.CREATED : BackfillOutcome.SKIPPED;
    }

    private String positionFunction() {
        if (positionFunction == null) positionFunction = jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) connection ->
                connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres") ? "STRPOS" : "INSTR");
        return positionFunction;
    }
}
