package com.ai.guide.domain.rag.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 知识文档实体
 * 原始 Python 模型由 sleepearlyplease 创建，Java 转化版本由 summerpalace2 实现
 */
@Data
public class KnowledgeDocument {
    private String id;
    private String title;
    private String category;
    private String scope = "UNSPECIFIED";
    private String city = "";
    private String content;
    private String tags;           // JSON array string
    private String fileUrl;
    private String fileMd5;
    private String sourceName;     // Qdrant payload 中用于关联该文档碎片的 source
    private String sourceUrl;      // Structured external reference; not an editorial approval flag.
    private String status;         // active / archived
    private String vectorStatus;   // pending / syncing / synced / failed
    private String indexRequestMode; // durable TEXT / FILE / DELETE request, or empty
    private Integer chunkCount;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
