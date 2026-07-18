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
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    /** Word 文档类型枚举 */
    private enum DocType { TABLE_DOMINANT, TEXT_DOMINANT }

    /** 当前处理的文件名（供解析器内部使用） */
    private String currentFileName;

    /** RAG 入库主方法：解析上传文件 → 去重 → 切割 → 向量化 → Qdrant 入库 */
    public void importUniversalDocument(MultipartFile file) throws Exception {
        String originalName = file.getOriginalFilename();
        String fileName = (originalName == null) ? "unknown" :
                new String(originalName.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);

        this.currentFileName = fileName;
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
            } else if (extension.equals("pdf")) {
                rawDocs = parsePdfStructured(inputStream);        // PDF: L1+L2+L3 三级处理
                if (rawDocs.isEmpty()) {
                    log.info("【PDF】PDFBox 提取为空，降级使用 Tika");
                    rawDocs = parseGeneralWithTika(file);
                }
            } else if (extension.equals("md") || extension.equals("markdown")) {
                rawDocs = parseMarkdownStructured(inputStream);   // Markdown: 标题层级+代码块保护
            } else if (extension.equals("txt")) {
                rawDocs = parseTxtStructured(inputStream);        // TXT: 段落合并+句子边界
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


    /**
     * Excel 结构化解析（增强版）：多Sheet支持 + 3行组合 + Sheet前缀 + ID列自动识别
     *
     * 增强策略：
     * - 遍历 workbook.getNumberOfSheets() 处理所有 Sheet
     * - Sheet 名作为前缀：【Sheet:名称】
     * - 3 行一组组合，保持行间关联
     * - 自动识别 ID 列（表头含"id/编号/代码"关键词）并提取 scenic_id
     *
     * @return 所有 Sheet 的碎片列表
     */
    private List<Document> parseExcelStructured(InputStream inputStream) throws Exception {
        List<Document> docs = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            int totalSheets = workbook.getNumberOfSheets();
            log.info("【Excel】开始处理，共 {} 个 Sheet", totalSheets);

            // 遍历所有 Sheet
            for (int sheetIdx = 0; sheetIdx < totalSheets; sheetIdx++) {
                Sheet sheet = workbook.getSheetAt(sheetIdx);
                String sheetName = sheet.getSheetName();

                Row headerRow = sheet.getRow(0);
                if (headerRow == null) continue;

                List<String> headers = new ArrayList<>();
                for (Cell cell : headerRow) headers.add(cell.getStringCellValue().trim());

                // ID 列自动识别
                int idColIdx = -1;
                for (int i = 0; i < headers.size(); i++) {
                    String h = headers.get(i).toLowerCase();
                    if (h.contains("id") || h.contains("编号") || h.contains("代码") || h.contains("代号")) {
                        idColIdx = i;
                        break;
                    }
                }

                // 3 行一组处理
                for (int i = 1; i <= sheet.getLastRowNum(); i += 3) {
                    int end = Math.min(i + 3, sheet.getLastRowNum() + 1);

                    // Sheet 前缀
                    StringBuilder identity = new StringBuilder("【Sheet:" + sheetName + "】\n");
                    StringBuilder rowContent = new StringBuilder(identity);

                    for (int r = i; r < end; r++) {
                        Row row = sheet.getRow(r);
                        if (row == null) continue;

                        for (int j = 0; j < headers.size(); j++) {
                            String val = getCellValue(row, j);
                            if (!val.isEmpty()) {
                                rowContent.append(headers.get(j)).append("是：").append(val).append("；\n");
                            }
                        }
                        rowContent.append("---\n");  // 行间分隔符
                    }

                    // 自动提取 scenic_id
                    Map<String, Object> metadata = new HashMap<>();
                    if (idColIdx >= 0) {
                        Row firstRow = sheet.getRow(i);
                        if (firstRow != null) {
                            String idVal = getCellValue(firstRow, idColIdx);
                            java.util.regex.Matcher m = idPattern.matcher(idVal);
                            if (m.find()) metadata.put("scenic_id", m.group());
                        }
                    }
                    // 也尝试从整块内容中匹配
                    if (!metadata.containsKey("scenic_id")) {
                        java.util.regex.Matcher m = idPattern.matcher(rowContent.toString());
                        if (m.find()) metadata.put("scenic_id", m.group());
                    }
                    metadata.put("sheet", sheetName);

                    docs.add(new Document(rowContent.toString(), metadata));
                }
            }
            log.info("【Excel】解析完成，生成 {} 条碎片", docs.size());
        }
        return docs;
    }

    /**
     * Word 文档类型检测
     * 统计表格行数与文本段落数的比例，判断文档主导类型
     *
     * @return TABLE_DOMINANT（表格行 > 10 且超过文本段落的 50%）或 TEXT_DOMINANT
     */
    private DocType detectDocType(XWPFDocument doc) {
        int tableRows = 0, textParagraphs = 0;
        for (IBodyElement element : doc.getBodyElements()) {
            if (element instanceof XWPFTable) {
                tableRows += ((XWPFTable) element).getRows().size();
            } else if (element instanceof XWPFParagraph p) {
                if (!p.getText().trim().isEmpty()) textParagraphs++;
            }
        }
        return (tableRows > 10 && tableRows > textParagraphs * 0.5)
                ? DocType.TABLE_DOMINANT : DocType.TEXT_DOMINANT;
    }

    /**
     * Word 结构化解析（增强版）：类型检测 + 3行组合 + 索引碎片生成
     *
     * 增强策略：
     * - TABLE_DOMINANT: 表格3行组合 + 生成索引碎片（含所有 scenic_id）
     * - TEXT_DOMINANT: 段落缓冲 + 句子边界感知切割
     *
     * @return 解析后的碎片列表
     */
    private List<Document> parseWordStructured(InputStream inputStream) throws Exception {
        List<Document> docs = new ArrayList<>();
        try (XWPFDocument doc = new XWPFDocument(inputStream)) {
            // Step 1: 文档类型检测
            DocType docType = detectDocType(doc);
            log.info("【Word】文档类型检测: {}", docType);

            List<String> allIds = new ArrayList<>();  // 收集所有 scenic_id 用于索引
            StringBuilder textBuffer = new StringBuilder();
            String lastParagraph = "";

            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    String currentText = para.getText().trim();
                    if (currentText.isEmpty()) continue;

                    textBuffer.append(currentText).append("\n");
                    if (textBuffer.length() > 600) {
                        // 句子边界感知：向前查找最近的句号位置
                        String bufferStr = textBuffer.toString();
                        int lastSentenceEnd = Math.max(
                                bufferStr.lastIndexOf("。"),
                                Math.max(bufferStr.lastIndexOf("！"), bufferStr.lastIndexOf("？"))
                        );
                        if (lastSentenceEnd > bufferStr.length() * 0.5) {
                            // 在句子边界切断
                            String chunk = bufferStr.substring(0, lastSentenceEnd + 1);
                            addDocWithExtractedMeta(docs, "【正文片段】\n" + chunk);
                            String remain = bufferStr.substring(lastSentenceEnd + 1).trim();
                            // 上下文衔接：前文提示 + 剩余内容
                            textBuffer = new StringBuilder();
                            textBuffer.append("(接前文：").append(lastParagraph).append("...)\n");
                            textBuffer.append(remain).append("\n");
                        } else {
                            addDocWithExtractedMeta(docs, "【正文片段】\n" + bufferStr);
                            textBuffer = new StringBuilder();
                            lastParagraph = currentText;
                            textBuffer.append("(接前文：").append(lastParagraph).append("...)\n");
                        }
                    }
                } else if (element instanceof XWPFTable table) {
                    // Step 2: 表格处理（根据类型选择策略）
                    if (docType == DocType.TABLE_DOMINANT) {
                        processTableGrouped(table, docs, allIds);
                    } else {
                        processWordTable(table, docs);
                    }
                }
            }

            // 写入剩余 buffer
            if (textBuffer.length() > 20) {
                addDocWithExtractedMeta(docs, "【正文片段】\n" + textBuffer.toString());
            }

            // Step 3: 表格主导时生成索引碎片
            if (docType == DocType.TABLE_DOMINANT && !allIds.isEmpty()) {
                String indexContent = "【文档索引】\n本文档包含以下景点/条目（共 " + allIds.size() + " 个）：\n"
                        + "- " + String.join("\n- ", allIds);
                docs.add(0, new Document(indexContent, new HashMap<>()));
                log.info("【Word】生成索引碎片，包含 {} 个景点", allIds.size());
            }
        }
        return docs;
    }

    /**
     * 表格处理：3 行组合（用于表格主导型文档）
     * 相邻 3 行组合为一个碎片，保持行间关联
     *
     * @param table   Word 表格对象
     * @param docs    碎片列表（输出）
     * @param allIds  scenic_id 收集器（用于生成索引）
     */
    private void processTableGrouped(XWPFTable table, List<Document> docs, List<String> allIds) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.size() < 2) return;

        List<String> headers = rows.get(0).getTableCells().stream()
                .map(c -> c.getText().trim()).toList();

        // 3 行一组处理
        for (int i = 1; i < rows.size(); i += 3) {
            StringBuilder sb = new StringBuilder("【表格条目】\n");
            int end = Math.min(i + 3, rows.size());
            for (int j = i; j < end; j++) {
                List<XWPFTableCell> cells = rows.get(j).getTableCells();
                for (int k = 0; k < Math.min(headers.size(), cells.size()); k++) {
                    sb.append(headers.get(k)).append(": ")
                            .append(cells.get(k).getText().trim()).append("; ");
                }
                sb.append("\n");
            }
            addDocWithExtractedMeta(docs, sb.toString());

            // 收集 scenic_id
            java.util.regex.Matcher m = idPattern.matcher(sb.toString());
            while (m.find()) allIds.add(m.group());
        }
    }

    /**
     * 表格处理：表头作为字段名，每行生成一个【表格条目】文档
     * （适用于文本主导型文档中的表格）
     */
    private void processWordTable(XWPFTable table, List<Document> docs) {
        List<XWPFTableRow> rows = table.getRows();
        if (rows.size() < 2) return;
        List<String> headers = rows.get(0).getTableCells().stream()
                .map(c -> c.getText().trim()).toList();
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

    /**
     * PDF 专属解析器：L1 文本提取 → L2 布局优化 → L3 OCR 兜底
     *
     * 处理策略：
     * - 先尝试 PDFBox 按页提取文本（L1）
     * - 多列布局时 setSortByPosition 保证文字顺序（L2）
     * - 提取内容过少时判定为扫描型 → Tika 降级（L3 OCR 需要 tess4j 依赖）
     *
     * @return 每页/每段切割后的碎片列表，含页码前缀
     */
    private List<Document> parsePdfStructured(InputStream inputStream) throws Exception {
        List<Document> docs = new ArrayList<>();
        try (PDDocument document = PDDocument.load(inputStream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);  // L2: 多列布局保证文字从上到下顺序

            int totalPages = document.getNumberOfPages();
            log.info("【PDF】开始处理，共 {} 页", totalPages);

            // L3 预判: 抽取前 3 页判断是否扫描型 PDF
            boolean isScanned = detectScannedPdf(document, stripper);
            if (isScanned) {
                log.warn("【PDF】判定为扫描型 PDF（前3页无有效文本），当前版本不支持 OCR，降级为 Tika 通用解析");
                return docs;  // 返回空列表，调用方 fallback 到 Tika
            }

            // L1+L2: 文字型 PDF 逐页处理
            String prevPageTail = "";  // 上一页末尾文本，用于跨页段落连接
            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);
                String pageText = stripper.getText(document);

                // L2: 去除页眉页脚
                pageText = removeHeaderFooter(pageText);

                // 跳过空白页
                if (pageText.trim().length() < 5) continue;

                // 段落切割（双换行为段落边界）
                List<String> chunks = splitPdfPage(pageText, prevPageTail);

                for (String chunk : chunks) {
                    // 身份前缀：来源 + 页码，便于溯源
                    String prefix = String.format("【来源:%s | 页码:%d】", currentFileName, pageNum);
                    addDocWithExtractedMeta(docs, prefix + "\n" + chunk);
                }

                // 保存本页末尾用于跨页段落连接（取最后 50 字符）
                prevPageTail = pageText.length() > 50
                        ? pageText.substring(Math.max(0, pageText.length() - 50))
                        : "";
            }
            log.info("【PDF】解析完成，生成 {} 条碎片", docs.size());
        }
        return docs;
    }

    /**
     * 判断是否为扫描型 PDF
     * 方法：抽样前 3 页，平均每页字符数 < 50 → 扫描型
     *
     * @return true = 扫描型（需要 OCR），false = 文字型
     */
    private boolean detectScannedPdf(PDDocument document, PDFTextStripper stripper) throws java.io.IOException {
        int pagesToCheck = Math.min(3, document.getNumberOfPages());
        if (pagesToCheck == 0) return false;

        int totalChars = 0;
        for (int i = 1; i <= pagesToCheck; i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String text = stripper.getText(document);
            text = removeHeaderFooter(text);
            totalChars += text.trim().length();
        }
        double avgCharsPerPage = (double) totalChars / pagesToCheck;
        log.info("【PDF】前{}页平均每页字符数: {}（<50 判定为扫描型）", pagesToCheck, String.format("%.1f", avgCharsPerPage));
        return avgCharsPerPage < 50;
    }

    /**
     * 去除 PDF 页眉页脚
     * 策略：移除第一行（如果是全大写含"第X页"特征）和最后一行（如果匹配页码模式）
     */
    private String removeHeaderFooter(String pageText) {
        String[] lines = pageText.split("\r?\n");
        if (lines.length <= 4) return pageText;

        int start = 0;
        String firstLine = lines[0].trim();
        if (firstLine.length() > 0 && firstLine.length() < 30
                && (firstLine.equals(firstLine.toUpperCase()) || firstLine.matches(".*第?\\d+.*页.*"))) {
            start = 1;
        }

        int end = lines.length;
        String lastLine = lines[lines.length - 1].trim();
        if (lastLine.matches("^[-]?\\d+[-]?$") || lastLine.matches("(?i)page.*\\d+")) {
            end = lines.length - 1;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    /**
     * 按段落切割单页 PDF 文本
     * 策略：双换行=段落边界 → 短段落合并 → 长段落按句子边界切割
     */
    private List<String> splitPdfPage(String pageText, String prevPageTail) {
        List<String> chunks = new ArrayList<>();
        String[] paragraphs = pageText.split("\n\s*\n");

        StringBuilder buffer = new StringBuilder();
        if (prevPageTail != null && !prevPageTail.isEmpty()) {
            buffer.append(prevPageTail).append(" ");
        }

        for (String para : paragraphs) {
            para = para.trim().replaceAll("\s+", " ");
            if (para.isEmpty()) continue;

            if (buffer.length() + para.length() < 200) {
                buffer.append(para).append(" ");
            } else {
                if (buffer.length() > 0) {
                    chunks.addAll(splitLongText(buffer.toString().trim()));
                    buffer = new StringBuilder();
                }
                if (para.length() > 400) {
                    chunks.addAll(splitLongText(para));
                } else {
                    buffer.append(para).append(" ");
                }
            }
        }
        if (buffer.length() > 0) {
            chunks.addAll(splitLongText(buffer.toString().trim()));
        }
        return chunks;
    }

    /**
     * 按句子边界切割长文本
     * 分隔符：。！？.!?
     */
    private List<String> splitLongText(String text) {
        List<String> chunks = new ArrayList<>();
        String[] sentences = text.split("(?<=[。！？.!?])\s*");

        StringBuilder chunk = new StringBuilder();
        for (String sentence : sentences) {
            if (chunk.length() + sentence.length() > 400) {
                if (chunk.length() > 0) {
                    chunks.add(chunk.toString().trim());
                    chunk = new StringBuilder();
                }
            }
            chunk.append(sentence);
        }
        if (chunk.length() > 0) {
            chunks.add(chunk.toString().trim());
        }
        return chunks;
    }

    /**
     * Markdown 专属解析器：按标题层级切割 + 代码块保护 + 标题路径前缀
     */
    private List<Document> parseMarkdownStructured(InputStream inputStream) throws Exception {
        List<Document> docs = new ArrayList<>();
        String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        String[] lines = content.split("\r?\n");

        StringBuilder currentChunk = new StringBuilder();
        java.util.Deque<String> headingStack = new java.util.ArrayDeque<>();
        StringBuilder headingPath = new StringBuilder();
        boolean inCodeBlock = false;
        String codeBlockMarker = "```";

        for (String line : lines) {
            if (line.trim().startsWith(codeBlockMarker)) {
                inCodeBlock = !inCodeBlock;
                currentChunk.append(line).append("\n");
                continue;
            }
            if (inCodeBlock) {
                currentChunk.append(line).append("\n");
                continue;
            }

            java.util.regex.Matcher headingMatcher =
                    java.util.regex.Pattern.compile("^(#{1,3})\\s+(.*)$").matcher(line);

            if (headingMatcher.find()) {
                if (currentChunk.length() > 0) {
                    String prefix = headingPath.length() > 0 ? "【" + headingPath + "】" : "";
                    addDocWithExtractedMeta(docs, prefix + "\n" + currentChunk.toString().trim());
                }
                int level = headingMatcher.group(1).length();
                String heading = headingMatcher.group(2).trim();
                while (headingStack.size() >= level) headingStack.pollLast();
                headingStack.offerLast(heading);
                headingPath = new StringBuilder();
                java.util.Iterator<String> it = headingStack.iterator();
                while (it.hasNext()) {
                    headingPath.append(it.next());
                    if (it.hasNext()) headingPath.append(" > ");
                }
                currentChunk = new StringBuilder(line).append("\n");
            } else {
                currentChunk.append(line).append("\n");
            }
        }

        if (currentChunk.length() > 0) {
            String prefix = headingPath.length() > 0 ? "【" + headingPath + "】" : "";
            addDocWithExtractedMeta(docs, prefix + "\n" + currentChunk.toString().trim());
        }

        log.info("【Markdown】解析完成，生成 {} 条碎片", docs.size());
        return docs;
    }


    /**
     * TXT 专属解析器：按段落分割 + 短句合并 + 句子边界切割
     *
     * 处理策略：
     * - 双换行符 = 段落边界
     * - 短段落（<200字符）合并到 buffer
     * - 长段落按句子边界切割（。！？.!?）
     *
     * @return 段落级碎片列表
     */
    private List<Document> parseTxtStructured(InputStream inputStream) throws Exception {
        List<Document> docs = new ArrayList<>();
        String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        String[] paragraphs = content.split("\\n\\s*\\n");

        StringBuilder buffer = new StringBuilder();
        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) continue;

            if (buffer.length() + para.length() < 200) {
                buffer.append(para).append(" ");
            } else {
                if (buffer.length() > 0) {
                    for (String s : splitLongText(buffer.toString().trim())) {
                        addDocWithExtractedMeta(docs, s);
                    }
                    buffer = new StringBuilder();
                }
                if (para.length() > 400) {
                    for (String s : splitLongText(para)) {
                        addDocWithExtractedMeta(docs, s);
                    }
                } else {
                    buffer.append(para).append(" ");
                }
            }
        }
        if (buffer.length() > 0) {
            for (String s : splitLongText(buffer.toString().trim())) {
                addDocWithExtractedMeta(docs, s);
            }
        }

        log.info("【TXT】解析完成，生成 {} 条碎片", docs.size());
        return docs;
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
        return queryKnowledge(queryText, 1500);
    }

    /**
     * 带超时参数的检索（正常模式传 800，深度模式传 1500）
     */
    public String queryKnowledge(String queryText, long timeoutMs) {
        log.info("【检索阶段】提问: {}，超时: {}ms", queryText, timeoutMs);
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
        List<String> finalDocs = rerankService.rerank(queryText, candidateDocs, 10, timeoutMs);
        return String.join("\n---\n", finalDocs);
    }

    /**
     * 检索知识碎片（参数化版本，供 Agentic RAG 并行检索使用）
     */
    public List<String> searchFragments(String queryText, int qdrantLimit, int rerankTopN) {
        if (queryText == null || queryText.isBlank()) {
            return Collections.emptyList();
        }

        // [步骤1] 向量化查询文本
        float[] queryVector = embeddingModel.embed(queryText);
        List<Float> vectorAsList = toFloatList(queryVector);

        // [步骤2] 标签过滤
        Points.Filter filter = null;
        Matcher matcher = idPattern.matcher(queryText.toUpperCase());
        if (matcher.find()) {
            String id = matcher.group();
            filter = Points.Filter.newBuilder()
                    .addMust(ConditionFactory.matchKeyword("scenic_id", id))
                    .build();
        }

        // [步骤3] Qdrant 向量检索
        Points.SearchPoints.Builder searchBuilder = Points.SearchPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .addAllVector(vectorAsList)
                .setLimit(qdrantLimit)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());
        if (filter != null) searchBuilder.setFilter(filter);

        List<Points.ScoredPoint> results;
        try {
            results = qdrantClient.searchAsync(searchBuilder.build()).get();
        } catch (Exception e) {
            return Collections.emptyList();
        }

        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> candidateDocs = new ArrayList<>();
        for (Points.ScoredPoint point : results) {
            JsonWithInt.Value contentVal = point.getPayloadMap().get("content");
            if (contentVal != null) {
                candidateDocs.add(contentVal.getStringValue());
            }
        }

        // [步骤4] 重排序
        if (candidateDocs.isEmpty()) {
            return Collections.emptyList();
        }
        return rerankService.rerank(queryText, candidateDocs, rerankTopN);
    }


    /**
     * 深度模式检索（跳过 L2/L3 语义缓存，保证每次都是新鲜结果）
     * 与 searchFragments 区别：使用 rerankDeep() 不用语义缓存
     */
    public List<String> searchFragmentsDeep(String queryText, int qdrantLimit, int rerankTopN) {
        if (queryText == null || queryText.isBlank()) {
            return Collections.emptyList();
        }

        // [步骤1] 向量化查询文本
        float[] queryVector = embeddingModel.embed(queryText);
        List<Float> vectorAsList = toFloatList(queryVector);

        // [步骤2] 标签过滤
        Points.Filter filter = null;
        Matcher matcher = idPattern.matcher(queryText.toUpperCase());
        if (matcher.find()) {
            String id = matcher.group();
            filter = Points.Filter.newBuilder()
                    .addMust(ConditionFactory.matchKeyword("scenic_id", id))
                    .build();
        }

        // [步骤3] Qdrant 向量检索
        Points.SearchPoints.Builder searchBuilder = Points.SearchPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .addAllVector(vectorAsList)
                .setLimit(qdrantLimit)
                .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());
        if (filter != null) searchBuilder.setFilter(filter);

        List<Points.ScoredPoint> results;
        try {
            results = qdrantClient.searchAsync(searchBuilder.build()).get();
        } catch (Exception e) {
            return Collections.emptyList();
        }

        if (results == null || results.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> candidateDocs = new ArrayList<>();
        for (Points.ScoredPoint point : results) {
            JsonWithInt.Value contentVal = point.getPayloadMap().get("content");
            if (contentVal != null) {
                candidateDocs.add(contentVal.getStringValue());
            }
        }

        // [步骤4] 深度重排序（跳过 L2/L3 语义缓存，保证质量）
        if (candidateDocs.isEmpty()) {
            return Collections.emptyList();
        }
        return rerankService.rerankDeep(queryText, candidateDocs, rerankTopN, 1500);
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
     * Count distinct source documents via scroll API
     */
    public int countDistinctSources() {
        try {
            Set<String> sources = ConcurrentHashMap.newKeySet();
            Points.ScrollPoints.Builder sb = Points.ScrollPoints.newBuilder()
                    .setCollectionName(COLLECTION_NAME)
                    .setLimit(1000)
                    .setWithPayload(Points.WithPayloadSelector.newBuilder().setEnable(true).build());
            Points.PointId offset = null;
            while (true) {
                if (offset != null) sb.setOffset(offset);
                Points.ScrollResponse resp;
                try { resp = qdrantClient.scrollAsync(sb.build()).get(); }
                catch (Exception ex) { break; }
                if (resp.getResultList().isEmpty()) break;
                for (Points.RetrievedPoint pt : resp.getResultList()) {
                    JsonWithInt.Value sv = pt.getPayloadMap().get("source");
                    if (sv != null) sources.add(sv.getStringValue());
                }
                offset = resp.getNextPageOffset();
                if (offset == null || !offset.hasNum() && !offset.hasUuid()) break;
            }
            return sources.size();
        } catch (Exception ex) {
            log.error("countDistinctSources failed: {}", ex.getMessage(), ex);
            return 0;
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


    /**
     * 从已保存的文件路径重新向量化（供知识库管理 sync 接口调用）
     * 由 summerpalace2 添加，用于 KnowledgeDocumentService.triggerSync()
     * 避免依赖 spring-test 的 MockMultipartFile，直接使用 CommonsMultipartFile
     */
    public void reindexDocument(File file) {
        if (file == null || !file.exists()) {
            throw new IllegalArgumentException("文件不存在: " + (file == null ? "null" : file.getPath()));
        }
        try {
            byte[] fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
            // 构造一个简易 MultipartFile 实现
            MultipartFile multipartFile = new ByteArrayMultipartFile(file.getName(), fileBytes);
            importUniversalDocument(multipartFile);
            log.info("[Knowledge] 重新向量化文件: {}", file.getName());
        } catch (Exception e) {
            log.error("[Knowledge] 重新向量化失败: {}", e.getMessage());
            throw new RuntimeException("重新向量化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 简易字节数组 MultipartFile 实现
     * 由 summerpalace2 实现，避免引入 spring-test 依赖
     */
    private static class ByteArrayMultipartFile implements MultipartFile {
        private final String name;
        private final byte[] content;

        ByteArrayMultipartFile(String name, byte[] content) {
            this.name = name;
            this.content = content;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public boolean isEmpty() { return content == null || content.length == 0; }
        @Override public long getSize() { return content != null ? content.length : 0; }
        @Override public byte[] getBytes() { return content; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(content); }
        @Override public void transferTo(File dest) throws java.io.IOException {
            java.nio.file.Files.write(dest.toPath(), content);
        }
    }

    /** float[] → List<Float> 便于传给 Qdrant 客户端 */
    private static List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }
}
