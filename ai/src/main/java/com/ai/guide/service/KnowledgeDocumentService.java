package com.ai.guide.service;

import com.ai.guide.model.KnowledgeDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

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

    public KnowledgeDocumentService(@org.springframework.beans.factory.annotation.Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                                    ScenicDataImportService scenicDataImportService) {
        this.jdbcTemplate = jdbcTemplate;
        this.scenicDataImportService = scenicDataImportService;
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
        StringBuilder sql = new StringBuilder("SELECT * FROM kb_document WHERE status != 'archived'");
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
     * 获取所有分类及其文档数
     */
    public List<Map<String, Object>> getCategories() {
        String sql = "SELECT category, COUNT(*) as cnt FROM kb_document WHERE status != 'archived' GROUP BY category";
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
                "SELECT COUNT(*) FROM kb_document WHERE title = ? AND status != 'archived'",
                Integer.class, title);
        if (count != null && count > 0) {
            throw new IllegalArgumentException("已存在同名文档: " + title);
        }

        String id = UUID.randomUUID().toString();
        String fileUrl = "";
        String fileMd5 = "";

        // 文件上传处理
        if (file != null && !file.isEmpty()) {
            fileMd5 = md5(file.getBytes());
            // MD5 去重
            Integer dupCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM kb_document WHERE file_md5 = ? AND status != 'archived'",
                    Integer.class, fileMd5);
            if (dupCount != null && dupCount > 0) {
                throw new IllegalArgumentException("文件已存在（相同 MD5）");
            }
            fileUrl = saveFile(file);
        }

        String now = LocalDateTime.now().format(DT_FMT);
        String sql = "INSERT INTO kb_document (id, title, category, content, tags, file_url, file_md5, status, vector_status, chunk_count, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'active', 'pending', 0, ?, ?, ?)";
        jdbcTemplate.update(sql, id, title, category, content != null ? content : "", tags != null ? tags : "[]", fileUrl, fileMd5, userId != null ? userId : "", now, now);

        log.info("[Knowledge] 创建文档 id={} title={}", id, title);

        // 异步触发向量化
        if (!fileUrl.isEmpty()) {
            triggerSyncAsync(id, fileUrl);
        }

        return getDocument(id);
    }

    /**
     * 更新文档元数据
     */
    public KnowledgeDocument updateDocument(String docId, String title, String category,
                                             String content, String tags) {
        KnowledgeDocument existing = getDocument(docId);
        if (existing == null) throw new IllegalArgumentException("Document not found: " + docId);

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("UPDATE kb_document SET updated_at = ?");
        params.add(LocalDateTime.now().format(DT_FMT));

        if (title != null) { sql.append(", title = ?"); params.add(title); }
        if (category != null) { sql.append(", category = ?"); params.add(category); }
        if (content != null) { sql.append(", content = ?"); params.add(content); }
        if (tags != null) { sql.append(", tags = ?"); params.add(tags); }

        sql.append(", vector_status = 'pending' WHERE id = ?");
        params.add(docId);

        jdbcTemplate.update(sql.toString(), params.toArray());
        log.info("[Knowledge] 更新文档 id={}", docId);

        // 异步触发重新向量化
        if (existing.getFileUrl() != null && !existing.getFileUrl().isEmpty()) {
            triggerSyncAsync(docId, existing.getFileUrl());
        }

        return getDocument(docId);
    }

    /**
     * 软删除文档
     */
    public void deleteDocument(String docId) {
        String sql = "UPDATE kb_document SET status = 'archived', updated_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, LocalDateTime.now().format(DT_FMT), docId);
        log.info("[Knowledge] 软删除文档 id={}", docId);
    }

    /**
     * 触发向量化同步
     */
    public Map<String, Object> triggerSync(String docId) {
        KnowledgeDocument doc = getDocument(docId);
        if (doc == null) throw new IllegalArgumentException("Document not found: " + docId);

        if (doc.getFileUrl() == null || doc.getFileUrl().isEmpty()) {
            throw new IllegalArgumentException("无可同步的文件");
        }

        // 更新状态为 syncing
        jdbcTemplate.update("UPDATE kb_document SET vector_status = 'syncing' WHERE id = ?", docId);

        try {
            // 调用 Java 端已有的导入服务
            scenicDataImportService.reindexDocument(new File(doc.getFileUrl()));
            jdbcTemplate.update("UPDATE kb_document SET vector_status = 'synced' WHERE id = ?", docId);
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

    /**
     * 异步触发向量化
     */
    private void triggerSyncAsync(String docId, String fileUrl) {
        new Thread(() -> {
            try {
                Thread.sleep(500); // 延迟确保事务已提交
                scenicDataImportService.reindexDocument(new File(fileUrl));
                jdbcTemplate.update("UPDATE kb_document SET vector_status = 'synced' WHERE id = ?", docId);
            } catch (Exception e) {
                jdbcTemplate.update("UPDATE kb_document SET vector_status = 'failed' WHERE id = ?", docId);
                log.error("[Knowledge] 异步向量化失败 id={}: {}", docId, e.getMessage());
            }
        }).start();
    }

    // ───────── 工具方法 ─────────

    private String saveFile(MultipartFile file) throws IOException {
        String uploadDir = "uploads/knowledge/";
        File dir = new File(uploadDir);
        if (!dir.exists()) dir.mkdirs();

        String originalName = file.getOriginalFilename();
        String ext = originalName != null && originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.')) : "";
        String filename = UUID.randomUUID().toString() + ext;
        File dest = new File(uploadDir + filename);
        file.transferTo(dest);
        return uploadDir + filename;
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
