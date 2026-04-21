package com.ai.guide.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.response.SearchResultsWrapper;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class ScenicDataImportService {

    private final MilvusServiceClient milvusClient;
    private final EmbeddingModel embeddingModel;
    private final RerankService rerankService;

    private final TokenTextSplitter nlpSplitter = new TokenTextSplitter(800, 200, 5, 10000, true);
    private final Pattern idPattern = Pattern.compile("[A-Z0-9]+-[0-9]+");

    public void importUniversalDocument(MultipartFile file) throws Exception {
        String originalName = file.getOriginalFilename();
        String fileName = (originalName == null) ? "unknown" :
                new String(originalName.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);

        log.info("【RAG系统】开始解析文件: {}", fileName);

        // 自动去重旧数据
        milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName("scenic_guide")
                .withExpr("metadata[\"source\"] == \"" + fileName + "\"").build());

        List<Document> rawDocs;
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();

        try (InputStream inputStream = file.getInputStream()) {
            if (extension.equals("xlsx") || extension.equals("xls")) {
                rawDocs = parseExcelStructured(inputStream);
            } else if (extension.equals("docx")) {
                rawDocs = parseWordStructured(inputStream);
            } else {
                rawDocs = parseGeneralWithTika(file);
            }
        }

        // ==========================================
        // 【防遗漏 1：二次切分与前缀继承逻辑】
        // ==========================================
        List<Document> finalDocs = new ArrayList<>();
        for (Document rawDoc : rawDocs) {
            String rc = rawDoc.getContent();

            // 提取结构化前缀（比如我们在 parseExcel 里加的 【 】）
            String prefix = "";
            if (rc.startsWith("【")) {
                int bracketEnd = rc.indexOf("】");
                if (bracketEnd != -1) {
                    prefix = rc.substring(0, bracketEnd + 1) + "\n(续): ";
                }
            }

            // 使用 nlpSplitter 确保单条数据不会超长
            List<Document> subDocs = nlpSplitter.apply(List.of(new Document(rc)));
            for (int i = 0; i < subDocs.size(); i++) {
                String subContent = subDocs.get(i).getContent();
                // 不是第一段的碎片，强制带上前面提取的【身份前缀】
                String finalContent = (i > 0 && !prefix.isEmpty()) ? prefix + subContent : subContent;

                Map<String, Object> meta = new HashMap<>(rawDoc.getMetadata());
                finalDocs.add(new Document(finalContent, meta));
            }
        }

        if (!finalDocs.isEmpty()) {
            insertToMilvus(finalDocs, fileName);
            log.info("【RAG系统】文件 {} 成功入库，共生成 {} 条语义碎片", fileName, finalDocs.size());
        }
    }

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

    private void addDocWithExtractedMeta(List<Document> docs, String content) {
        Map<String, Object> metadata = new HashMap<>();
        Matcher matcher = idPattern.matcher(content);
        if (matcher.find()) {
            metadata.put("scenic_id", matcher.group());
        }
        docs.add(new Document(content, metadata));
    }

    private List<Document> parseGeneralWithTika(MultipartFile file) throws Exception {
        TikaDocumentReader reader = new TikaDocumentReader(new InputStreamResource(file.getInputStream()));
        return reader.get();
    }

    private void insertToMilvus(List<Document> docs, String sourceName) {
        List<String> ids = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<JSONObject> metadataObjects = new ArrayList<>();

        for (Document doc : docs) {
            String cleanText = (doc.getContent() != null) ? doc.getContent().replace("\uFFFD", " ").trim() : "";
            if (cleanText.length() < 3) continue;

            try {
                float[] v = embeddingModel.embed(cleanText);
                List<Float> vList = new ArrayList<>();
                for (float f : v) vList.add(f);

                // 【关键点 2】直接构建 JSONObject，不要转 String
                JSONObject meta = new JSONObject();
                if (doc.getMetadata() != null) {
                    meta.putAll(doc.getMetadata());
                }
                meta.put("source", sourceName);

                ids.add(UUID.randomUUID().toString());
                vectors.add(vList);
                contents.add(cleanText);
                metadataObjects.add(meta); // 直接添加对象

            } catch (Exception e) { log.error("处理失败: {}", e.getMessage()); }
        }

        if (!ids.isEmpty()) {
            milvusClient.insert(InsertParam.newBuilder()
                    .withCollectionName("scenic_guide")
                    .withFields(List.of(
                            new InsertParam.Field("id", ids),
                            new InsertParam.Field("vector", vectors),
                            new InsertParam.Field("content", contents),
                            new InsertParam.Field("metadata", metadataObjects)
                    )).build());
        }
    }

    private String getCellValue(Row row, int idx) {
        if (idx < 0) return "未知";
        Cell cell = row.getCell(idx);
        return (cell == null) ? "" : new DataFormatter().formatCellValue(cell).trim();
    }

    public String queryKnowledge(String queryText) {
        log.info("【检索阶段】提问: {}", queryText);

        float[] queryVector = embeddingModel.embed(queryText);
        List<Float> vectorAsList = new ArrayList<>();
        for (float v : queryVector) vectorAsList.add(v);

        String expr = "";
        Matcher matcher = idPattern.matcher(queryText.toUpperCase());
        if (matcher.find()) {
            String id = matcher.group();
            expr = "metadata[\"scenic_id\"] == \"" + id + "\"";
            log.info("【极速标签匹配】触发意图识别, ID: {}, Expr: {}", id, expr);
        }

        SearchParam.Builder searchBuilder = SearchParam.newBuilder()
                .withCollectionName("scenic_guide")
                .withMetricType(MetricType.COSINE)
                .withOutFields(List.of("content"))
                .withTopK(30)
                .withVectors(List.of(vectorAsList))
                .withVectorFieldName("vector");

        if (!expr.isEmpty()) searchBuilder.withExpr(expr);

        R<io.milvus.grpc.SearchResults> response = milvusClient.search(searchBuilder.build());

        if (response.getStatus() != 0 || response.getData() == null || response.getData().getResults().getFieldsDataCount() == 0) {
            log.warn("【检索无果】");
            return "知识库暂无相关数据。";
        }

        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<String> candidateDocs = new ArrayList<>();
        try {
            List<?> fieldData = wrapper.getFieldData("content", 0);
            for (Object data : fieldData) candidateDocs.add(data.toString());
        } catch (Exception e) {
            log.error("字段提取失败: {}", e.getMessage());
            return "";
        }

        List<String> finalDocs = rerankService.rerank(queryText, candidateDocs, 10);
        return String.join("\n---\n", finalDocs);
    }
}