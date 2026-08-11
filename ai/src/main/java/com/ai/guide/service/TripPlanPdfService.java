package com.ai.guide.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/** 最小行程投影；只输出状态标签，不虚构事实。 */
@Service
public class TripPlanPdfService {

    public byte[] export(Map<String, Object> plan) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 42, 42, 42, 42);
            PdfWriter.getInstance(document, output);
            document.open();
            BaseFont baseFont = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
            Font title = new Font(baseFont, 18, Font.BOLD);
            Font body = new Font(baseFont, 10, Font.NORMAL);

            document.add(new Paragraph(text(plan.get("title"), "未命名行程"), title));
            document.add(new Paragraph("数据状态：" + text(plan.get("dataStatus"), "UNVERIFIED"), body));
            document.add(new Paragraph("提示：动态字段、价格、营业时间、路线与来源需以最新真实数据核验。", body));
            document.add(new Paragraph(" ", body));

            Object daysValue = plan.get("days");
            if (daysValue instanceof List<?> days) {
                for (Object dayValue : days) {
                    if (!(dayValue instanceof Map<?, ?> day)) continue;
                    document.add(new Paragraph("D" + text(day.get("day"), "") + "  " + text(day.get("title"), "行程日"), body));
                    Object stopsValue = day.get("stops");
                    if (stopsValue instanceof List<?> stops && !stops.isEmpty()) {
                        for (Object stopValue : stops) {
                            if (!(stopValue instanceof Map<?, ?> stop)) continue;
                            document.add(new Paragraph("- " + text(stop.get("name"), "未命名景点") + "（详情待核验）", body));
                        }
                    } else {
                        document.add(new Paragraph("- 暂无已确认景点", body));
                    }
                }
            }
            document.close();
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("行程 PDF 生成失败", e);
        }
    }

    private String text(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        return String.valueOf(value);
    }
}
