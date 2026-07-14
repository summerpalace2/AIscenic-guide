package com.ai.guide.service;

import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ============================================
 * RAG 知识库全流程服务
 * ============================================
 *
 * 入库流程：
 *
 * 上传文件
 *   → 1. 文档解析：Excel 结构化解析 / Word 结构化解析 / Tika 通用解析
 *   → 2. 去重：按 source 字段删除 Qdrant 中旧数据
 *   → 3. TokenTextSplitter 切割：每段 800 tokens，重叠 200 tokens
 *   → 4. 前缀继承：切割后的碎片继承原始文档的【身份前缀】，防止语义丢失
 *   → 5. text-embedding-v2 向量化：1536 维向量
 *   → 6. Qdrant 入库：写入 scenic_guide 集合
 *
 *
 * 检索流程：
 *
 * 用户提问
 *   → 1. 向量化查询文本
 *   → 2. 标签过滤：若问题含 scenic_id，追加 payload 过滤表达式
 *   → 3. Qdrant 向量检索（COSINE 相似度，TOP30）
 *   → 4. gte-rerank-v2 重排序（TOP30 → TOP10）
 *   → 5. 返回拼接后的 TOP10 文本
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScenicDataImportService {

    private static final String COLLECTION_NAME = "scenic_guide";

    private final QdrantClient qdrantClient;
    private final EmbeddingModel embeddingModel;
    private final RerankService rerankService;

    private final TokenTextSplitter nlpSplitter = new TokenTextSplitter(800, 200, 5, 10000, true);
    private final Pattern idPattern = Pattern.compile("[A-Z0-9]+-[0-9]+");

    /** RAG 入库主方法：解析上传文件 → 去重 → 切割 → 向量化 → Qdrant 入库 */
    public void importUniversalDocument(MultipartFile file) throws Exception {
        String originalName = file.getOriginalFilename();
        String fileName = (originalName == null) ? "unknown" :
                new String(originalName.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);

        log.info("【RAG系统】开始解析文件: {}", fileName);

        // [步骤1] 自动去重旧数据：删除 Qdrant 中同一文件来源的旧向量
        try {
            qdrantClient.deleteAsync(COLLECTION_NAME,
                    Points.Filter.newBuilder()
                            .addMust(ConditionFactory.matchKeyword("source", fileName))
                            .build()).get();
        } catch (Exception e) {
            log.warn("【Qdrant 去重失败（可忽略，首次导入无旧数据）】{}", e.getMessage());
        }

        List<Document> rawDocs;
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();

        // [步骤2] 文档解析：根据文件类型选择对应解析器
        try (InputStream inputStream = file.getInputStream()) {
            if (extension.equals("xlsx") || extension.equals("xls")) {
                rawDocs = parseExcelStructured(inputStream);      // Excel: 按行解析，表头作为字段名
            } else if (extension.equals("docx")) {
                rawDocs = parseWordStructured(inputStream);       // Word: 段落+表格混合解析
            } else {
                rawDocs = parseGeneralWithTika(file);             // 其他格式: Apache Tika 通用解析
            }
        }

        // ==========================================
        // 【防遗漏：二次切分与前缀继承逻辑】
        // ==========================================
        // 原因：原始文档可能包含超长段落，必须二次切割。
        // 但切割会导致碎片丢失【身份前缀】（如"景点名称:灵山大佛"），
        // 因此对每个非首段碎片强制补上前缀，确保检索时不会因为缺前缀而漏掉。
        List<Document> finalDocs = new ArrayList<>();
        for (Document rawDoc : rawDocs) {
            String rc = rawDoc.getContent();

            // 提取结构化前缀（在 parseExcel/parseWord 中生成的【xxx】标记）
            String prefix = "";
            if (rc.startsWith("【")) {
                int bracketEnd = rc.indexOf("】");
                if (bracketEnd != -1) {
                    prefix = rc.substring(0, bracketEnd + 1) + "\n(续): ";
                }
            }

            // [步骤3] 使用 nlpSplitter 进行二次切割，确保单条数据不超 token 上限
            List<Document> subDocs = nlpSplitter.apply(List.of(new Document(rc)));
            for (int i = 0; i < subDocs.size(); i++) {
                String subContent = subDocs.get(i).getContent();
                // [前缀继承] 非首段碎片强制补上【身份前缀】，防止语义断裂
                String finalContent = (i > 0 && !prefix.isEmpty()) ? prefix + subContent : subContent;

                Map<String, Object> meta = new HashMap<>(rawDoc.getMetadata());
                finalDocs.add(new Document(finalContent, meta));
            }
        }

        // [步骤4] 向量化 + Qdrant 入库
        if (!finalDocs.isEmpty()) {
            insertToQdrant(finalDocs, fileName);
            log.info("【RAG系统】文件 {} 成功入库，共生成 {} 条语义碎片", fileName, finalDocs.size());
        }
    }

    /** Excel 结构化解析：表头作为字段名，每行生成一个文档（带【】前缀） */
    private List<Document> parseExcelStructured(InputStream inputStream) throws Exception {
        List<Document> docs = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return docs;

            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) headers.add(cell.getStringCellValue().trim());

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                StringBuilder identity = new StringBuilder("【");
                for (int k = 0; k < Math.min(2, headers.size()); k++) {
                    identity.append(headers.get(k)).append(":").append(getCellValue(row, k)).append(" ");
                }
                identity.append("】\n");

                StringBuilder rowContent = new StringBuilder(identity);
                for (int j = 0; j < headers.size(); j++) {
                    String val = getCellValue(row, j);
                    if (!val.isEmpty()) {
                        rowContent.append(headers.get(j)).append("是：").append(val).append("；\n");
                    }
                }

                Map<String, Object> metadata = new HashMap<>();
                Matcher matcher = idPattern.matcher(rowContent.toString());
                if (matcher.find()) {
                    metadata.put("scenic_id", matcher.group());
                }
                docs.add(new Document(rowContent.toString(), metadata));
            }
        }
        return docs;
    }

    /** Word 结构化解析：段落按 600 字符分段，表格按行提取，并添加上下文衔接 */
    private List<Document> parseWordStructured(InputStream inputStream) throws Exception {
        List<Document> docs = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(inputStream)) {
            StringBuilder textBuffer = new StringBuilder();
            String lastParagraph = "";

            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    String currentText = para.getText().trim();
                    if (currentText.isEmpty()) continue;

                    textBuffer.append(currentText).append("\n");
                    if (textBuffer.length() > 600) {
                        addDocWithExtractedMeta(docs, "【正文片段】\n" + textBuffer.toString());
                        lastParagraph = currentText;
                        textBuffer.setLength(0);
                        textBuffer.append("(接前文：").append(lastParagraph).append("...)\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    processWordTable(table, docs);
                }
            }
            if (textBuffer.length() > 20) {
                addDocWithExtractedMeta(docs, "【正文片段】\n" + textBuffer.toString());
            }
        }
        return docs;
    }

    /** Word 表格处理：表头作为字段名，每行生成一个【表格条目】文档 */
    private void processWordTable(XWPFTable table, List<Document> docs) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.size() < 2) return;
        List<String> headers = rows.get(0).getTableCells().stream().map(c -> c.getText().trim()).toList();
        for (int i = 1; i < rows.size(); i++) {
            StringBuilder sb = new StringBuilder("【表格条目】\n");
            List<XWPFTableCell> cells = rows.get(i).getTableCells();
            for (int j = 0; j < Math.min(headers.size(), cells.size()); j++) {
                sb.append(headers.get(j)).append(": ").append(cells.get(j).getText().trim()).append("; ");
            }
            addDocWithExtractedMeta(docs, sb.toString());
        }
    }

    /** 添加文档时自动提取 scenic_id 作为元数据 */
    private void addDocWithExtractedMeta(List<Document> docs, String content) {
        Map<String, Object> metadata = new HashMap<>();
        Matcher matcher = idPattern.matcher(content);
        if (matcher.find()) {
            metadata.put("scenic_id", matcher.group());
        }
        docs.add(new Document(content, metadata));
    }

    /** Tika 通用文档解析（PDF/Markdown/TXT 等非结构化格式） */
    private List<Document> parseGeneralWithTika(MultipartFile file) throws Exception {
        TikaDocumentReader reader = new TikaDocumentReader(new InputStreamResource(file.getInputStream()));
        return reader.get();
    }

    /** 批量向量化并写入 Qdrant（一条文档 → 一个 1536 维向量） */
    private void insertToQdrant(List<Document> docs, String sourceName) {
        List<Points.PointStruct> points = new ArrayList<>();

        for (Document doc : docs) {
            String cleanText = (doc.getContent() != null) ? doc.getContent().replace("\uFFFD", " ").trim() : "";
            if (cleanText.length() < 3) continue;

            try {
                float[] v = embeddingModel.embed(cleanText);

                Map<String, JsonWithInt.Value> payload = new HashMap<>();
                payload.put("content", ValueFactory.value(cleanText));
                payload.put("source", ValueFactory.value(sourceName));
                if (doc.getMetadata() != null) {
                    for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                        if (entry.getValue() != null) {
                            payload.put(entry.getKey(), ValueFactory.value(entry.getValue().toString()));
                        }
                    }
                }

                Points.PointStruct point = Points.PointStruct.newBuilder()
                        .setId(PointIdFactory.id(UUID.randomUUID()))
                        .setVectors(VectorsFactory.vectors(toFloatList(v)))
                        .putAllPayload(payload)
                        .build();
                points.add(point);

            } catch (Exception e) { log.error("处理失败: {}", e.getMessage()); }
        }

        if (!points.isEmpty()) {
            try {
                qdrantClient.upsertAsync(COLLECTION_NAME, points).get();
            } catch (Exception e) {
                log.error("【Qdrant 入库失败】{}", e.getMessage());
            }
        }
    }

    /** 从 Excel 单元格安全取值 */
    private String getCellValue(Row row, int idx) {
        if (idx < 0) return "未知";
        Cell cell = row.getCell(idx);
        return (cell == null) ? "" : new DataFormatter().formatCellValue(cell).trim();
    }

    /**
     * 知识库检索主方法
     * <p>
     * <b>检索步骤：</b>
     * <ol>
     *   <li>向量化查询文本（text-embedding-v2 → 1536 维）</li>
     *   <li>标签过滤：若问题含 scenic_id 格式，追加 payload 过滤条件</li>
     *   <li>Qdrant COSINE 检索 TOP30</li>
     *   <li>gte-rerank-v2 重排序 TOP30 → TOP10</li>
     *   <li>返回拼接后的知识文本</li>
     * </ol>
     */
    public String queryKnowledge(String queryText) {
        log.info("【检索阶段】提问: {}", queryText);

        // [步骤1] 向量化查询文本
        float[] queryVector = embeddingModel.embed(queryText);
        List<Float> vectorAsList = toFloatList(queryVector);

        // [步骤2] 标签过滤：检测问题中是否包含 scenic_id 模式，有则精确过滤
        Points.Filter filter = null;
        Matcher matcher = idPattern.matcher(queryText.toUpperCase());
        if (matcher.find()) {
            String id = matcher.group();
            filter = Points.Filter.newBuilder()
                    .addMust(ConditionFactory.matchKeyword("scenic_id", id))
                    .build();
            log.info("【极速标签匹配】触发意图识别, ID: {}", id);
        }

        // [步骤3] Qdrant 向量检索：COSINE 相似度，TOP30
        Points.SearchPoints.Builder searchBuilder = Points.SearchPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .addAllVector(vectorAsList)
                .setLimit(30)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());
        if (filter != null) searchBuilder.setFilter(filter);

        List<Points.ScoredPoint> results;
        try {
            results = qdrantClient.searchAsync(searchBuilder.build()).get();
        } catch (Exception e) {
            log.error("【检索异常】{}", e.getMessage());
            return "";
        }

        if (results == null || results.isEmpty()) {
            log.warn("【检索无果】");
            return "知识库暂无相关数据。";
        }

        List<String> candidateDocs = new ArrayList<>();
        for (Points.ScoredPoint point : results) {
            JsonWithInt.Value contentVal = point.getPayloadMap().get("content");
            if (contentVal != null) {
                candidateDocs.add(contentVal.getStringValue());
            }
        }

        // [步骤4] 重排序：TOP30 候选 → gte-rerank-v2 → 精选 TOP10
        List<String> finalDocs = rerankService.rerank(queryText, candidateDocs, 10);
        return String.join("\n---\n", finalDocs);
    }


    /**
     * 统计 Qdrant 中所有碎片数量
     */
    public long countDocuments() {
        try {
            return qdrantClient.countAsync(COLLECTION_NAME).get();
        } catch (Exception e) {
            log.error("countDocuments failed: {}", e.getMessage(), e);
            return -1;
        }
    }

    /**
     * 清空知识库所有数据
     */
    public void deleteAllDocuments() {
        try {
            qdrantClient.deleteAsync(COLLECTION_NAME, Points.Filter.newBuilder().build(), java.time.Duration.ofSeconds(30)).get();
            log.info("【知识库清空】已删除全部数据");
        } catch (Exception e) {
            log.error("deleteAllDocuments failed: {}", e.getMessage(), e);
            throw new RuntimeException("知识库清空失败", e);
        }
    }
    /** float[] → List<Float> 便于传给 Qdrant 客户端 */
    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }
}
