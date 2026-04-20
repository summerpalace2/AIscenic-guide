package com.ai.guide.service;

import com.alibaba.fastjson.JSONObject;
import io.milvus.client.MilvusServiceClient;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScenicDataImportService {

    private final MilvusServiceClient milvusClient;
    private final EmbeddingModel embeddingModel;
    private final RerankService rerankService;

    //NLP 切分器：800 长度，200 重叠（Overlap），确保上下文不断档
    private final TokenTextSplitter nlpSplitter = new TokenTextSplitter(800, 200, 5, 10000, true);


    /**
     * 数据导入总函数：所有解析出来的“初步片段”都要经过 nlpSplitter 二次处理
     */
    public void importUniversalDocument(MultipartFile file) throws Exception {
        String originalName = file.getOriginalFilename();
        String fileName = (originalName == null) ? "unknown" :
                new String(originalName.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);

        log.info("【RAG系统】开始解析文件: {}", fileName);

        // 自动去重
        milvusClient.delete(DeleteParam.newBuilder()
                .withCollectionName("scenic_guide")
                .withExpr("metadata[\"source\"] == \"" + fileName + "\"").build());

        List<String> rawChunks = new ArrayList<>();
        String extension = fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();

        try (InputStream inputStream = file.getInputStream()) {
            if (extension.equals("xlsx") || extension.equals("xls")) {
                rawChunks = parseExcelStructured(inputStream);
            } else if (extension.equals("docx")) {
                rawChunks = parseWordStructured(inputStream);
            } else {
                rawChunks = parseGeneralWithTika(file);
            }
        }

        // --- 【核心修正 1：处理二次切分逻辑】 ---
        List<String> finalChunks = new ArrayList<>();
        for (String rc : rawChunks) {
            String prefix = "";
            if (rc.startsWith("【")) {
                prefix = rc.substring(0, rc.indexOf("】") + 1) + "\n(续): ";
            }

            List<Document> subDocs = nlpSplitter.apply(List.of(new Document(rc)));
            for (int i = 0; i < subDocs.size(); i++) {
                String subContent = subDocs.get(i).getContent();
                // 如果是切开的后续块，把前缀补回去
                finalChunks.add(i > 0 && !prefix.isEmpty() ? prefix + subContent : subContent);
            }
        }

        // --- 【核心修正 2：必须调用插入方法！！】 ---
        if (!finalChunks.isEmpty()) {
            insertToMilvus(finalChunks, fileName);
            log.info("【RAG系统】成功入库 {} 条知识碎片", finalChunks.size());
        }
    }

    /**
     * Excel 结构化解析：将每一行转为“带身份标签”的完整句子
     */
    private List<String> parseExcelStructured(InputStream inputStream) throws Exception {
        List<String> chunks = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return chunks;

            // 1. 自动提取所有表头名
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) headers.add(cell.getStringCellValue().trim());

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                // 2. 【通用化改进】：不再找固定名字，而是默认取前两列作为“身份前缀”
                // 无论你第一列叫什么，AI 都会把它的值作为上下文
                StringBuilder identity = new StringBuilder("【");
                for (int k = 0; k < Math.min(2, headers.size()); k++) {
                    identity.append(headers.get(k)).append(":").append(getCellValue(row, k)).append(" ");
                }
                identity.append("】\n");

                // 3. 将整行转化为自描述段落
                StringBuilder rowContent = new StringBuilder(identity);
                for (int j = 0; j < headers.size(); j++) {
                    String val = getCellValue(row, j);
                    if (!val.isEmpty()) {
                        // 格式：[表头]是[内容]；
                        // 例如：门票价格是：120元；
                        rowContent.append(headers.get(j)).append("是：").append(val).append("；\n");
                    }
                }
                chunks.add(rowContent.toString());
            }
        }
        return chunks;
    }

    /**
     * Word 结构化解析：文本采用滑动窗口 Overlap，表格采用 Identity Injection
     */
    private List<String> parseWordStructured(InputStream inputStream) throws Exception {
        List<String> chunks = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(inputStream)) {
            StringBuilder textBuffer = new StringBuilder();
            String lastParagraph = ""; // 用于实现手动 Overlap

            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    String currentText = para.getText().trim();
                    if (currentText.isEmpty()) continue;

                    // 滑动窗口策略：每积累一定量文本，封装为一个 Chunk
                    textBuffer.append(currentText).append("\n");
                    if (textBuffer.length() > 600) {
                        chunks.add("【正文片段】\n" + textBuffer.toString());
                        // 保留当前段落作为下一段的“重叠上下文”
                        lastParagraph = currentText;
                        textBuffer.setLength(0);
                        textBuffer.append("(接前文：").append(lastParagraph).append("...)\n");
                    }
                } else if (element instanceof XWPFTable table) {
                    // 表格处理：将表格行转化为带标题的独立段落
                    processWordTable(table, chunks);
                }
            }
            if (textBuffer.length() > 20) chunks.add("【正文片段】\n" + textBuffer.toString());
        }
        return chunks;
    }

    private void processWordTable(XWPFTable table, List<String> chunks) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.size() < 2) return;
        // 获取列名
        List<String> headers = rows.get(0).getTableCells().stream().map(c -> c.getText().trim()).toList();
        for (int i = 1; i < rows.size(); i++) {
            StringBuilder sb = new StringBuilder("【表格条目】");
            List<XWPFTableCell> cells = rows.get(i).getTableCells();
            for (int j = 0; j < Math.min(headers.size(), cells.size()); j++) {
                sb.append(headers.get(j)).append(": ").append(cells.get(j).getText().trim()).append("; ");
            }
            chunks.add(sb.toString());
        }
    }

    /**
     * 兜底解析（Tika）：处理 PDF 等其他格式，应用标准的 NLP Overlap 切分
     */
    private List<String> parseGeneralWithTika(MultipartFile file) throws Exception {
        TikaDocumentReader reader = new TikaDocumentReader(new InputStreamResource(file.getInputStream()));
        List<Document> rawDocs = reader.get();
        // 使用自带 Overlap 的切分器
        List<Document> splitDocs = nlpSplitter.apply(rawDocs);
        return splitDocs.stream().map(Document::getContent).toList();
    }

    /**
     * 最终写入 Zilliz：强制匹配 Schema 字段 id, vector, content, metadata
     */
    private void insertToMilvus(List<String> chunks, String sourceName) {
        List<String> ids = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        List<String> contents = new ArrayList<>();
        List<JSONObject> metadatas = new ArrayList<>(); // 保持 JSONObject

        for (String chunk : chunks) {
            String cleanText = chunk.replace("\uFFFD", " ").trim();
            if (cleanText.length() < 3) continue;

            try {
                float[] v = embeddingModel.embed(cleanText);
                List<Float> vList = new ArrayList<>();
                for (float f : v) vList.add(f);

                ids.add(UUID.randomUUID().toString());
                vectors.add(vList);
                contents.add(cleanText);

                JSONObject meta = new JSONObject();
                meta.put("source", sourceName);
                metadatas.add(meta);
            } catch (Exception e) { log.error("向量化失败: {}", e.getMessage()); }
        }

        if (!ids.isEmpty()) {
            milvusClient.insert(InsertParam.newBuilder()
                    .withCollectionName("scenic_guide")
                    .withFields(List.of(
                            new InsertParam.Field("id", ids),
                            new InsertParam.Field("vector", vectors),
                            new InsertParam.Field("content", contents),
                            new InsertParam.Field("metadata", metadatas)
                    )).build());
        }
    }

    /**
     * ：既能防止 -1 报错，又使用了 DataFormatter 保证数字不乱码
     */
    private String getCellValue(Row row, int idx) {
        // 防御：如果列不存在，返回未知
        if (idx < 0) return "未知";

        Cell cell = row.getCell(idx);
        // 防御：如果单元格是空的，返回空字符串
        if (cell == null) return "";

        // 核心：使用 DataFormatter 处理各种复杂的 Excel 格式
        return new DataFormatter().formatCellValue(cell).trim();
    }

    /**
     * 工业级检索：向量语义召回 (广度优先) + 精准编号过滤 + Rerank 重排序 (精度优先)
     */
    public String queryKnowledge(String queryText) {
        log.info("【检索阶段】开始处理用户提问: {}", queryText);

        // 1. 生成查询向量
        float[] queryVector = embeddingModel.embed(queryText);
        List<Float> vectorAsList = new ArrayList<>();
        for (float v : queryVector) vectorAsList.add(v);

        // 2. 构造过滤表达式 (精准 ID 匹配)
        String expr = "";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[A-Z0-9]+-[0-9]+");
        java.util.regex.Matcher matcher = pattern.matcher(queryText.toUpperCase());
        if (matcher.find()) {
            String id = matcher.group();
            expr = "content like \"%" + id + "%\"";
            log.info("【精准模式】检测到编号 {}, 已启用硬匹配过滤", id);
        }

        // 3. 执行 Milvus 初筛 (广撒网策略)
        // 将 TopK 调大到 30，确保即使有很多相似的“灵山精舍”描述占位，后面的“梵宫/素面”也能进入候选名单
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName("scenic_guide")
                .withMetricType(io.milvus.param.MetricType.COSINE)
                .withOutFields(List.of("content"))
                .withTopK(30) // 初筛 30 条数据
                .withVectors(List.of(vectorAsList))
                .withVectorFieldName("vector")
                .withExpr(expr)
                .build();

        R<io.milvus.grpc.SearchResults> response = milvusClient.search(searchParam);

        if (response.getStatus() != 0) {
            log.error("【检索异常】Milvus 检索失败: {}", response.getMessage());
            return "";
        }

        // 4. 解析检索到的候选文档
        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<?> fieldData = wrapper.getFieldData("content", 0);

        if (fieldData == null || fieldData.isEmpty()) {
            log.warn("【检索无果】Milvus 未能找到任何匹配片段");
            return "";
        }

        List<String> candidateDocs = new ArrayList<>();
        for (Object data : fieldData) {
            candidateDocs.add(data.toString());
        }
        log.info("【检索反馈】Milvus 初筛完成，已获取 {} 条候选知识片段", candidateDocs.size());

        // 5. 执行 Rerank 重排序 (精挑选策略)
        // 利用重排模型从 30 条中选出最相关的 10 条。
        // 调大返回给 AI 的数量至 10，能让 AI 拥有“全景视角”，看到所有餐饮选项。
        List<String> finalDocs = rerankService.rerank(queryText, candidateDocs, 10);

        log.info("【检索反馈】重排序完成，最终选取 {} 条高质量背景知识提供给 AI", finalDocs.size());

        // 使用自定义分割线连接，方便 AI 区分不同的知识块
        return String.join("\n---\n", finalDocs);
    }
}