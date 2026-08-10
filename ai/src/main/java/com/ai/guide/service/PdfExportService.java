package com.ai.guide.service;

import com.lowagie.text.Font;
import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * PDF 报告导出 Service
 * 生成日报/周报的 PDF 文件
 *
 * 原始 Python 由 sleepearlyplease 创建 (backend/app/services/pdf_service.py)
 * Java 转化版本由 summerpalace2 实现
 */
@Service
public class PdfExportService {

    private static final Logger log = LoggerFactory.getLogger(PdfExportService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbcTemplate;

    public PdfExportService(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 导出日报 PDF
     * @param date 日期 (yyyy-MM-dd)，null 表示昨天
     * @return PDF 字节数组
     */
    public byte[] exportDailyReport(String date) {
        if (date == null) date = LocalDate.now().minusDays(1).format(DATE_FMT);
        String dayStart = date + " 00:00:00";
        String dayEnd = LocalDate.parse(date).plusDays(1).format(DATE_FMT) + " 00:00:00";

        Integer total = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM service_log WHERE created_at >= ? AND created_at < ?", Integer.class, dayStart, dayEnd);
        if (total == null) total = 0;

        Integer pos = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM service_log WHERE emotion = 'POSITIVE' AND created_at >= ? AND created_at < ?", Integer.class, dayStart, dayEnd);
        if (pos == null) pos = 0;

        Integer neg = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM service_log WHERE emotion = 'NEGATIVE' AND created_at >= ? AND created_at < ?", Integer.class, dayStart, dayEnd);
        if (neg == null) neg = 0;

        List<Map<String, Object>> hotQuestions = jdbcTemplate.query(
            "SELECT question, COUNT(*) as cnt FROM service_log WHERE created_at >= ? AND created_at < ? AND question != '' GROUP BY question ORDER BY cnt DESC LIMIT 5",
            (rs, rowNum) -> Map.of("rank", rowNum + 1, "question", rs.getString("question"), "count", rs.getInt("cnt")),
            dayStart, dayEnd
        );

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 50, 50, 50, 50);
            PdfWriter.getInstance(document, baos);
            document.open();

            BaseFont bf = getChineseFont();
            Font titleFont = new Font(bf, 22, Font.BOLD, new Color(0x63, 0x66, 0xF1));
            Font h2Font = new Font(bf, 14, Font.BOLD, new Color(0x2D, 0x37, 0x48));
            Font bodyFont = new Font(bf, 11, Font.NORMAL, new Color(0x33, 0x33, 0x33));
            Font smallFont = new Font(bf, 10, Font.NORMAL, new Color(0x71, 0x80, 0x96));

            Paragraph title = new Paragraph("灵山智慧导游 - 日服务报告", titleFont);
            title.setAlignment(Element.ALIGN_CENTER);
            title.setSpacingAfter(10);
            document.add(title);

            Paragraph dateP = new Paragraph("报告日期: " + date, smallFont);
            dateP.setAlignment(Element.ALIGN_CENTER);
            dateP.setSpacingAfter(30);
            document.add(dateP);

            document.add(new Paragraph("一、数据概览", h2Font));
            document.add(Chunk.NEWLINE);

            PdfPTable statTable = new PdfPTable(2);
            statTable.setWidthPercentage(60);
            statTable.setHorizontalAlignment(Element.ALIGN_CENTER);
            addStatRow(statTable, "总服务次数", total.toString(), bf);
            addStatRow(statTable, "正面情绪", pos.toString(), bf);
            addStatRow(statTable, "负面情绪", neg.toString(), bf);
            addStatRow(statTable, "满意度", total > 0 ? String.format("%.0f%%", (pos * 100.0 / total)) : "N/A", bf);
            document.add(statTable);
            document.add(Chunk.NEWLINE);

            document.add(new Paragraph("二、热门问题 TOP5", h2Font));
            document.add(Chunk.NEWLINE);

            for (Map<String, Object> q : hotQuestions) {
                document.add(new Paragraph("  " + q.get("rank") + ". " + q.get("question") + " (" + q.get("count") + "次)", bodyFont));
            }
            if (hotQuestions.isEmpty()) {
                document.add(new Paragraph("  暂无数据", smallFont));
            }

            document.add(Chunk.NEWLINE);
            document.add(new Paragraph("三、情绪分布", h2Font));
            document.add(Chunk.NEWLINE);

            PdfPTable emoTable = new PdfPTable(3);
            emoTable.setWidthPercentage(80);
            emoTable.setHorizontalAlignment(Element.ALIGN_CENTER);
            addHeaderCell(emoTable, "情绪", bf);
            addHeaderCell(emoTable, "次数", bf);
            addHeaderCell(emoTable, "占比", bf);
            addEmoRow(emoTable, "正面", pos, total, bf);
            addEmoRow(emoTable, "负面", neg, total, bf);
            addEmoRow(emoTable, "中性", total - pos - neg, total, bf);
            document.add(emoTable);

            document.add(Chunk.NEWLINE);
            Paragraph footer = new Paragraph("本报告由灵山智慧导游系统自动生成", smallFont);
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

            document.close();
            log.info("[PDF] 日报导出完成: {} ({} bytes)", date, baos.size());
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("[PDF] 生成失败: {}", e.getMessage());
            return null;
        }
    }

    private void addStatRow(PdfPTable table, String label, String value, BaseFont bf) {
        Font font = new Font(bf, 12, Font.NORMAL);
        PdfPCell labelCell = new PdfPCell(new Phrase(label, font));
        labelCell.setPadding(8);
        labelCell.setBackgroundColor(new Color(0xF7, 0xFA, 0xFC));
        table.addCell(labelCell);
        PdfPCell valueCell = new PdfPCell(new Phrase(value, font));
        valueCell.setPadding(8);
        valueCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(valueCell);
    }

    private void addHeaderCell(PdfPTable table, String text, BaseFont bf) {
        Font font = new Font(bf, 11, Font.BOLD, Color.WHITE);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(new Color(0x63, 0x66, 0xF1));
        cell.setPadding(8);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(cell);
    }

    private void addEmoRow(PdfPTable table, String emotion, int count, int total, BaseFont bf) {
        Font font = new Font(bf, 11, Font.NORMAL);
        PdfPCell c1 = new PdfPCell(new Phrase(emotion, font));
        c1.setPadding(6);
        c1.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(c1);
        PdfPCell c2 = new PdfPCell(new Phrase(String.valueOf(count), font));
        c2.setPadding(6);
        c2.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(c2);
        PdfPCell c3 = new PdfPCell(new Phrase(total > 0 ? String.format("%.1f%%", (count * 100.0 / total)) : "N/A", font));
        c3.setPadding(6);
        c3.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(c3);
    }

    private BaseFont getChineseFont() {
        try {
            String[] fontPaths = {
                "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc,0",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0",
                "/System/Library/Fonts/PingFang.ttc,0",
                "C:/Windows/Fonts/msyh.ttc,0",
                "C:/Windows/Fonts/simhei.ttf"
            };
            for (String path : fontPaths) {
                try { return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED); } catch (Exception e) { /* try next */ }
            }
            return BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.EMBEDDED);
        } catch (Exception e) {
            try { return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.EMBEDDED); }
            catch (Exception ex) { throw new RuntimeException("无法创建字体", ex); }
        }
    }
}
