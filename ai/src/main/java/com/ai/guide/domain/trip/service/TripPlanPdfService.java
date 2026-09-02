package com.ai.guide.domain.trip.service;

import com.lowagie.text.Anchor;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 权威 Java 行程手册 PDF 导出服务。
 * 遵循专业旅行手册信息架构：封面与总览、每日时间线、景点卡片、高德路线直达与出行贴士。
 */
@Service
public class TripPlanPdfService {

    public byte[] export(Map<String, Object> plan) {
        if (plan == null) plan = Map.of();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 36, 36, 40, 36);
            PdfWriter writer = PdfWriter.getInstance(document, output);

            BaseFont baseFont = createChineseFont();
            String nowStr = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(LocalDateTime.now());
            String sourceMode = text(plan.get("sourceMode"), "来源待确认");
            writer.setPageEvent(new PageHeaderFooterEvent(baseFont, nowStr, sourceMode));

            document.open();

            // 统一视觉调色板
            Color primaryColor = new Color(24, 76, 102);     // #184C66 山城江岸青
            Color accentColor = new Color(214, 90, 36);      // #D65A24 暮色晚霞橙
            Color darkText = new Color(33, 37, 41);          // #212529 正文主色
            Color mutedText = new Color(108, 117, 125);      // #6C757D 次要文本
            Color cardBg = new Color(248, 250, 252);         // #F8FAFC 卡片底色
            Color cardBorder = new Color(226, 232, 240);     // #E2E8F0 边框微色
            Color headerBg = new Color(237, 244, 248);       // #EDF4F8 表头底色
            Color noticeBg = new Color(254, 251, 240);       // #FEFBF0 提示底色
            Color noticeBorder = new Color(246, 224, 134);   // #F6E086 提示边框
            Color linkColor = new Color(13, 110, 253);       // #0D6EFD 链接蓝

            // 字体定义
            Font eyebrowFont = new Font(baseFont, 8.5f, Font.BOLD, accentColor);
            Font titleFont = new Font(baseFont, 16f, Font.BOLD, primaryColor);
            Font subtitleFont = new Font(baseFont, 9.5f, Font.NORMAL, mutedText);
            Font dayHeaderFont = new Font(baseFont, 10.5f, Font.BOLD, primaryColor);
            Font cardNameFont = new Font(baseFont, 11f, Font.BOLD, darkText);
            Font cardBodyFont = new Font(baseFont, 8.5f, Font.NORMAL, darkText);
            Font cardMutedFont = new Font(baseFont, 8f, Font.NORMAL, mutedText);
            Font tagFont = new Font(baseFont, 7.5f, Font.BOLD, primaryColor);
            Font linkFont = new Font(baseFont, 8f, Font.NORMAL, linkColor);
            Font timeBigFont = new Font(baseFont, 11f, Font.BOLD, accentColor);
            Font noticeTitleFont = new Font(baseFont, 9f, Font.BOLD, new Color(133, 100, 4));
            Font noticeBodyFont = new Font(baseFont, 8f, Font.NORMAL, new Color(100, 75, 5));

            // 行程数据提取
            String title = text(plan.get("title"), "重庆旅行手册");
            String subtitle = text(plan.get("subtitle"), "按你的偏好整理的重庆旅行路线");
            Map<?, ?> constraints = plan.get("constraints") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> planContext = plan.get("planContext") instanceof Map<?, ?> m ? m : Map.of();
            Map<?, ?> sourceStatus = plan.get("sourceStatus") instanceof Map<?, ?> m ? m : Map.of();
            List<?> days = plan.get("days") instanceof List<?> list ? list : List.of();

            int totalStops = 0;
            for (Object d : days) {
                if (d instanceof Map<?, ?> day && day.get("stops") instanceof List<?> stops) {
                    totalStops += stops.size();
                }
            }

            // 1. 顶部品牌与行程概览
            Paragraph eyebrow = new Paragraph("悠悠智策 · CHONGQING TRAVEL HANDBOOK", eyebrowFont);
            eyebrow.setSpacingBefore(0);
            eyebrow.setSpacingAfter(2);
            document.add(eyebrow);

            Paragraph mainTitle = new Paragraph(title, titleFont);
            mainTitle.setSpacingAfter(3);
            document.add(mainTitle);

            Paragraph subTitlePara = new Paragraph(subtitle, subtitleFont);
            subTitlePara.setSpacingAfter(10);
            document.add(subTitlePara);

            // 行程关键概要卡片
            PdfPTable overviewTable = new PdfPTable(4);
            overviewTable.setWidthPercentage(100);
            overviewTable.setWidths(new float[]{25f, 25f, 25f, 25f});
            overviewTable.setSpacingAfter(10);

            String durationText = days.isEmpty() ? "未提供" : days.size() + " 天";
            String companionsText = displayValue(constraints.get("companions"));
            String walkingText = displayValue(constraints.get("walkingTolerance"));
            String transportText = displayValue(constraints.containsKey("transportPreference")
                    ? constraints.get("transportPreference") : planContext.get("routePreference"));
            String budgetText = displayValue(constraints.get("budget"));
            String dietText = displayValue(constraints.get("dietPreference"));
            String stayAreaText = displayValue(constraints.containsKey("stayArea")
                    ? constraints.get("stayArea") : planContext.get("startingArea"));

            overviewTable.addCell(createOverviewCell("游玩周期", durationText, baseFont, headerBg, cardBorder));
            overviewTable.addCell(createOverviewCell("景点总数", totalStops + " 处精选站", baseFont, headerBg, cardBorder));
            overviewTable.addCell(createOverviewCell("同行画像", companionsText, baseFont, headerBg, cardBorder));
            overviewTable.addCell(createOverviewCell("步行偏好", walkingText, baseFont, headerBg, cardBorder));
            overviewTable.addCell(createOverviewCell("出行方式", transportText, baseFont, headerBg, cardBorder));
            overviewTable.addCell(createOverviewCell("风味餐饮", dietText, baseFont, headerBg, cardBorder));
            overviewTable.addCell(createOverviewCell("预算策略", budgetText, baseFont, headerBg, cardBorder));
            overviewTable.addCell(createOverviewCell("出发参考", stayAreaText, baseFont, headerBg, cardBorder));

            document.add(overviewTable);

            String sourceSummary = sourceSummary(sourceMode, sourceStatus);
            String tripMetadata = tripMetadata(plan);
            if (!sourceSummary.isBlank() || !tripMetadata.isBlank()) {
                PdfPTable sourceTable = new PdfPTable(1);
                sourceTable.setWidthPercentage(100);
                sourceTable.setSpacingAfter(8);
                String sourceLine = sourceSummary.isBlank() ? "" : "数据来源：" + sourceSummary;
                String metadataLine = tripMetadata.isBlank() ? "" : "行程记录：" + tripMetadata;
                String sourceText = sourceLine.isBlank() ? metadataLine
                        : metadataLine.isBlank() ? sourceLine : sourceLine + "\n" + metadataLine;
                PdfPCell sourceCell = new PdfPCell(new Phrase(sourceText, cardMutedFont));
                sourceCell.setBackgroundColor(new Color(244, 248, 250));
                sourceCell.setBorderColor(new Color(207, 220, 228));
                sourceCell.setPadding(6);
                sourceTable.addCell(sourceCell);
                document.add(sourceTable);
            }

            // 餐饮或路线策略引导（若有）
            String foodGuidance = text(planContext.get("foodGuidance"), "");
            if (foodGuidance.startsWith("饮食策略：")) foodGuidance = foodGuidance.substring("饮食策略：".length()).trim();
            if (foodGuidance.startsWith("餐饮策略：")) foodGuidance = foodGuidance.substring("餐饮策略：".length()).trim();
            if (!foodGuidance.isBlank() && !foodGuidance.contains("未指定")) {
                PdfPTable tipTable = new PdfPTable(1);
                tipTable.setWidthPercentage(100);
                tipTable.setSpacingAfter(8);
                PdfPCell cell = new PdfPCell(new Phrase("餐饮策略：" + foodGuidance, cardBodyFont));
                cell.setBackgroundColor(new Color(240, 247, 250));
                cell.setBorderColor(new Color(200, 225, 235));
                cell.setPadding(6);
                tipTable.addCell(cell);
                document.add(tipTable);
            }

            // 2. 每日时间线与景点卡片
            for (Object dayObj : days) {
                if (!(dayObj instanceof Map<?, ?> day)) continue;
                int dayNumber = number(day.get("day"), 1);
                String dateLabel = text(day.get("dateLabel"), text(day.get("date"), "第 " + dayNumber + " 天"));

                // 天气信息
                String weatherStr = "";
                if (day.get("weather") instanceof Map<?, ?> w) {
                    String wVal = text(w.get("value"), "");
                    if (!wVal.isBlank() && !wVal.contains("未知")) {
                        weatherStr = "  天气：" + wVal;
                    }
                }

                // 每日 Banner
                PdfPTable dayBanner = new PdfPTable(2);
                dayBanner.setWidthPercentage(100);
                dayBanner.setWidths(new float[]{75f, 25f});
                dayBanner.setSpacingBefore(8);
                dayBanner.setSpacingAfter(6);

                PdfPCell dayTitleCell = new PdfPCell(new Phrase("DAY " + dayNumber + " · " + dateLabel, dayHeaderFont));
                dayTitleCell.setBorder(Rectangle.NO_BORDER);
                dayTitleCell.setPaddingBottom(3);

                PdfPCell weatherCell = new PdfPCell(new Phrase(weatherStr, cardMutedFont));
                weatherCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                weatherCell.setBorder(Rectangle.NO_BORDER);
                weatherCell.setPaddingBottom(3);

                dayBanner.addCell(dayTitleCell);
                dayBanner.addCell(weatherCell);
                document.add(dayBanner);

                // 出发衔接提示
                String departureContext = text(day.get("departureContext"), "");
                if (!departureContext.isBlank() && !departureContext.contains("未指定")) {
                    Paragraph depPara = new Paragraph("出发衔接：" + departureContext, cardMutedFont);
                    depPara.setSpacingAfter(5);
                    document.add(depPara);
                }

                // 站点卡片列表
                List<?> stops = day.get("stops") instanceof List<?> list ? list : List.of();
                for (int i = 0; i < stops.size(); i++) {
                    Object stopObj = stops.get(i);
                    if (!(stopObj instanceof Map<?, ?> stop)) continue;

                    PdfPTable cardTable = new PdfPTable(2);
                    cardTable.setWidthPercentage(100);
                    cardTable.setWidths(new float[]{18f, 82f});
                    cardTable.setKeepTogether(true);
                    cardTable.setSpacingAfter(4);

                    // 左侧：时间、序号与游玩时长
                    PdfPCell leftCell = new PdfPCell();
                    leftCell.setBackgroundColor(cardBg);
                    leftCell.setBorderColor(cardBorder);
                    leftCell.setPadding(6);
                    leftCell.setHorizontalAlignment(Element.ALIGN_CENTER);

                    String stopTime = displayValue(stop.containsKey("time") ? stop.get("time") : stop.get("startTime"));
                    String duration = displayValue(stop.get("duration"));

                    Paragraph timePara = new Paragraph(stopTime, timeBigFont);
                    timePara.setAlignment(Element.ALIGN_CENTER);
                    leftCell.addElement(timePara);

                    Paragraph stopIndexPara = new Paragraph("第 0" + (i + 1) + " 站", tagFont);
                    stopIndexPara.setAlignment(Element.ALIGN_CENTER);
                    leftCell.addElement(stopIndexPara);

                    Paragraph durPara = new Paragraph(duration, cardMutedFont);
                    durPara.setAlignment(Element.ALIGN_CENTER);
                    leftCell.addElement(durPara);

                    // 右侧：景点详情、推荐依据、到达交通与导航链接
                    PdfPCell rightCell = new PdfPCell();
                    rightCell.setBackgroundColor(Color.WHITE);
                    rightCell.setBorderColor(cardBorder);
                    rightCell.setPadding(6);

                    String stopName = text(stop.get("name"), text(stop.get("displayName"), "未命名景点"));
                    String district = text(stop.get("district"), "");
                    String ticket = text(stop.get("ticket"), "");

                    Phrase namePhrase = new Phrase();
                    namePhrase.add(new Chunk(stopName, cardNameFont));
                    String metadata = joinMetadata(district, ticket);
                    if (!metadata.isBlank()) namePhrase.add(new Chunk("  " + metadata, tagFont));
                    rightCell.addElement(new Paragraph(namePhrase));

                    String summary = text(stop.get("summary"), text(stop.get("detail"), ""));
                    if (!summary.isBlank()) {
                        Paragraph summaryPara = new Paragraph(summary, cardBodyFont);
                        summaryPara.setSpacingBefore(2);
                        summaryPara.setSpacingAfter(2);
                        rightCell.addElement(summaryPara);
                    }

                    String reason = text(stop.get("recommendationReason"), "");
                    if (!reason.isBlank() && !reason.equals(summary)) {
                        Paragraph reasonPara = new Paragraph("推荐理由：" + reason, cardMutedFont);
                        reasonPara.setSpacingAfter(2);
                        rightCell.addElement(reasonPara);
                    }

                    String routeText = routeText(stop, planContext);
                    if (!routeText.isBlank()) {
                        Paragraph routePara = new Paragraph("到达方式：" + routeText, cardMutedFont);
                        routePara.setSpacingAfter(2);
                        rightCell.addElement(routePara);
                    }

                    // 高德官方目的地链接；没有坐标时明确标为搜索，不伪装成精确导航。
                    String location = text(stop.get("location"), "");
                    String routeMode = routeMode(stop, planContext);
                    String navUrl = buildAmapNavUrl(stopName, location, routeMode);
                    Anchor navLink = new Anchor(hasCoordinates(location)
                            ? "在高德地图打开目的地" : "在高德地图搜索目的地", linkFont);
                    navLink.setReference(navUrl);
                    Paragraph linkPara = new Paragraph();
                    linkPara.add(navLink);
                    linkPara.setSpacingBefore(2);
                    rightCell.addElement(linkPara);

                    cardTable.addCell(leftCell);
                    cardTable.addCell(rightCell);
                    document.add(cardTable);

                    // 下一站的真实路线信息挂在下一站 stop.routeFromPrevious 上。
                    if (i < stops.size() - 1 && stops.get(i + 1) instanceof Map<?, ?> nextStop) {
                        String transition = routeText(nextStop, planContext);
                        if (!transition.isBlank()) {
                            PdfPTable transitTable = new PdfPTable(1);
                            transitTable.setWidthPercentage(100);
                            transitTable.setSpacingAfter(3);
                            PdfPCell transitCell = new PdfPCell(new Phrase("↓  下一站：" + transition, cardMutedFont));
                            transitCell.setBorder(Rectangle.NO_BORDER);
                            transitCell.setPadding(0);
                            transitTable.addCell(transitCell);
                            document.add(transitTable);
                        }
                    }
                }
            }

            // 3. 出行贴士与动态核验说明
            PdfPTable noticeTable = new PdfPTable(1);
            noticeTable.setWidthPercentage(100);
            noticeTable.setKeepTogether(true);
            noticeTable.setSpacingBefore(10);
            noticeTable.setSpacingAfter(6);

            PdfPCell noticeCell = new PdfPCell();
            noticeCell.setBackgroundColor(noticeBg);
            noticeCell.setBorderColor(noticeBorder);
            noticeCell.setBorderWidth(1f);
            noticeCell.setPadding(8);

            Paragraph noticeTitle = new Paragraph("出行贴士与动态核验说明", noticeTitleFont);
            noticeTitle.setSpacingAfter(3);
            noticeCell.addElement(noticeTitle);

            noticeCell.addElement(new Paragraph("• 门票、开放时间、预约、交通和天气等动态信息可能变化，出发前请再次核验景区官方公告或高德实时结果。", noticeBodyFont));
            noticeCell.addElement(new Paragraph("• 行程中的路线摘要只有在标记为动态时才代表本次查询结果；未返回的路线会明确标为待核验。", noticeBodyFont));
            noticeCell.addElement(new Paragraph("• 卡片链接会打开高德官方目的地页面；缺少坐标时仅执行名称搜索，请在出发前确认具体入口。", noticeBodyFont));

            noticeTable.addCell(noticeCell);
            document.add(noticeTable);

            document.close();
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("行程 PDF 生成失败", e);
        }
    }

    private static PdfPCell createOverviewCell(String label, String value, BaseFont font, Color bg, Color border) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(bg);
        cell.setBorderColor(border);
        cell.setPadding(4);
        Font labelFont = new Font(font, 7.5f, Font.NORMAL, new Color(120, 130, 140));
        Font valFont = new Font(font, 8.5f, Font.BOLD, new Color(30, 40, 50));
        cell.addElement(new Paragraph(label, labelFont));
        cell.addElement(new Paragraph(value, valFont));
        return cell;
    }

    private static String buildAmapNavUrl(String name, String location, String mode) {
        String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8);
        String coords = location == null ? "" : location.trim();
        if (hasCoordinates(coords)) {
            String[] parts = coords.split(",");
            StringBuilder url = new StringBuilder("https://uri.amap.com/navigation?to=")
                    .append(parts[0].trim()).append(',').append(parts[1].trim()).append(',').append(encodedName);
            if (mode != null && !mode.isBlank()) url.append("&mode=").append(mode);
            return url.append("&src=yuyouzhice&coordinate=gaode&callnative=1").toString();
        }
        return "https://uri.amap.com/search?keyword=" + encodedName + "&city=500000&src=yuyouzhice&callnative=1";
    }

    private static boolean hasCoordinates(String location) {
        if (location == null || location.isBlank()) return false;
        String[] parts = location.trim().split(",");
        if (parts.length != 2) return false;
        try {
            Double.parseDouble(parts[0].trim());
            Double.parseDouble(parts[1].trim());
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String routeMode(Map<?, ?> stop, Map<?, ?> planContext) {
        Object route = stop.get("routeFromPrevious");
        if (route instanceof Map<?, ?> routeMap) {
            String selectedMode = String.valueOf(objectOr(routeMap, "selectedMode", ""));
            if (selectedMode.contains("公共交通")) return "bus";
            if (selectedMode.contains("步行")) return "walk";
        }
        Object preferenceValue = stop.get("routePreference");
        if (preferenceValue == null) preferenceValue = planContext.get("routePreference");
        String preference = preferenceValue == null ? "" : String.valueOf(preferenceValue);
        if (preference.contains("transit") || preference.contains("公共交通") || preference.contains("公交") || preference.contains("地铁")) return "bus";
        if (preference.contains("walk") || preference.contains("步行")) return "walk";
        if (preference.contains("taxi") || preference.contains("驾车") || preference.contains("打车")) return "car";
        return "";
    }

    private static String routeText(Map<?, ?> stop, Map<?, ?> planContext) {
        Object route = stop.get("routeFromPrevious");
        if (route instanceof Map<?, ?> routeMap) {
            Object selected = routeMap.get("selected");
            if (selected instanceof Map<?, ?> selectedMap && "动态".equals(String.valueOf(selectedMap.get("status")))) {
                String mode = String.valueOf(objectOr(routeMap, "selectedMode", "路线"));
                String distance = distanceText(selectedMap.get("distanceMeters"), selectedMap.get("distance"));
                String duration = durationText(selectedMap.get("durationSeconds"), selectedMap.get("duration"));
                String metrics = joinMetadata(distance, duration);
                return mode + (metrics.isBlank() ? "" : " · " + metrics);
            }
            String reason = String.valueOf(objectOr(routeMap, "reason", ""));
            return reason.isBlank() ? "路线待核验" : "路线待核验（" + reason + "）";
        }
        Map<?, ?> walkingInfo = stop.get("walkingInfo") instanceof Map<?, ?> info ? info : Map.of();
        String status = String.valueOf(objectOr(walkingInfo, "status", ""));
        String summary = String.valueOf(objectOr(walkingInfo, "summary", ""));
        if ("动态".equals(status) && !summary.isBlank()) return summary;
        String walk = String.valueOf(objectOr(stop, "walk", ""));
        return walk.isBlank() || "路线待计算".equals(walk) ? "" : walk + "（静态参考，待核验）";
    }

    private static Object objectOr(Map<?, ?> map, String key, Object fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        return value == null ? fallback : value;
    }

    private static String distanceText(Object primary, Object fallback) {
        Object value = primary == null ? fallback : primary;
        if (value == null || String.valueOf(value).isBlank()) return "";
        try {
            double meters = Double.parseDouble(String.valueOf(value));
            return meters >= 1000 ? String.format("%.1f 公里", meters / 1000d) : Math.round(meters) + " 米";
        } catch (NumberFormatException ignored) {
            return String.valueOf(value);
        }
    }

    private static String durationText(Object primary, Object fallback) {
        Object value = primary == null ? fallback : primary;
        if (value == null || String.valueOf(value).isBlank()) return "";
        try {
            double seconds = Double.parseDouble(String.valueOf(value));
            return Math.max(1, Math.round(seconds / 60d)) + " 分钟";
        } catch (NumberFormatException ignored) {
            return String.valueOf(value);
        }
    }

    private static String joinMetadata(String first, String second) {
        if (first == null || first.isBlank()) return second == null ? "" : second;
        if (second == null || second.isBlank()) return first;
        return first + " · " + second;
    }

    private static String sourceSummary(String sourceMode, Map<?, ?> sourceStatus) {
        StringBuilder result = new StringBuilder(sourceMode == null ? "" : sourceMode);
        Object coverage = sourceStatus.get("dataCoverage");
        if (coverage != null && !String.valueOf(coverage).isBlank()) result.append(" · 覆盖度 ").append(coverage);
        Object fallback = sourceStatus.get("fallback");
        if (Boolean.TRUE.equals(fallback)) result.append(" · 部分信息待核验");
        return result.toString();
    }

    private static String tripMetadata(Map<String, Object> plan) {
        StringBuilder result = new StringBuilder();
        Object version = plan.get("currentVersion");
        if (version != null && !String.valueOf(version).isBlank()) {
            result.append("第 ").append(version).append(" 版");
        }
        String updatedAt = timestampText(plan.get("tripUpdatedAt"));
        if (!updatedAt.isBlank()) {
            if (result.length() > 0) result.append(" · ");
            result.append("保存于 ").append(updatedAt);
        }
        return result.toString();
    }

    private static String timestampText(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return "";
        if (value instanceof Number number) {
            try {
                return java.time.Instant.ofEpochMilli(number.longValue())
                        .atZone(java.time.ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            } catch (RuntimeException ignored) {
                return "";
            }
        }
        return String.valueOf(value);
    }

    private static String displayValue(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "未指定".equals(String.valueOf(value))) return "未提供";
        return String.valueOf(value);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try { return Integer.parseInt(value.toString().trim()); } catch (Exception ignored) {}
        }
        return fallback;
    }

    private String text(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        return String.valueOf(value);
    }

    private BaseFont createChineseFont() throws Exception {
        String[] candidates = {
                "C:/Windows/Fonts/msyh.ttc,0",
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/simsun.ttc,0",
                "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,0",
                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc,0",
                "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc,0"
        };
        for (String path : candidates) {
            String filePath = path.endsWith(",0") ? path.substring(0, path.length() - 2) : path;
            if (!new File(filePath).exists()) continue;
            try {
                return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception ignored) {
            }
        }
        return BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
    }

    static class PageHeaderFooterEvent extends PdfPageEventHelper {
        private final BaseFont baseFont;
        private final String generatedDate;
        private final String sourceMode;

        public PageHeaderFooterEvent(BaseFont baseFont, String generatedDate, String sourceMode) {
            this.baseFont = baseFont;
            this.generatedDate = generatedDate;
            this.sourceMode = sourceMode == null || sourceMode.isBlank() ? "来源待确认" : sourceMode;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Font headerFont = new Font(baseFont, 7.5f, Font.NORMAL, new Color(140, 150, 160));
            Font footerFont = new Font(baseFont, 7.5f, Font.NORMAL, new Color(140, 150, 160));

            // Top Header
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setTotalWidth(document.right() - document.left());
            try {
                headerTable.setWidths(new float[]{70f, 30f});

                PdfPCell leftCell = new PdfPCell(new Phrase("悠悠智策 · 重庆专属定制旅行手册", headerFont));
                leftCell.setBorder(Rectangle.BOTTOM);
                leftCell.setBorderColor(new Color(220, 226, 232));
                leftCell.setBorderWidth(0.5f);
                leftCell.setPaddingBottom(3);

                PdfPCell rightCell = new PdfPCell(new Phrase(sourceMode, headerFont));
                rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                rightCell.setBorder(Rectangle.BOTTOM);
                rightCell.setBorderColor(new Color(220, 226, 232));
                rightCell.setBorderWidth(0.5f);
                rightCell.setPaddingBottom(3);

                headerTable.addCell(leftCell);
                headerTable.addCell(rightCell);
                headerTable.writeSelectedRows(0, -1, document.left(), document.top() + 18, writer.getDirectContent());
            } catch (Exception ignored) {}

            // Bottom Footer
            PdfPTable footerTable = new PdfPTable(2);
            footerTable.setTotalWidth(document.right() - document.left());
            try {
                footerTable.setWidths(new float[]{60f, 40f});

                PdfPCell leftFooter = new PdfPCell(new Phrase("生成时间：" + generatedDate + " · 动态信息请出行前核验", footerFont));
                leftFooter.setBorder(Rectangle.TOP);
                leftFooter.setBorderColor(new Color(220, 226, 232));
                leftFooter.setBorderWidth(0.5f);
                leftFooter.setPaddingTop(3);

                PdfPCell rightFooter = new PdfPCell(new Phrase("第 " + writer.getPageNumber() + " 页", footerFont));
                rightFooter.setHorizontalAlignment(Element.ALIGN_RIGHT);
                rightFooter.setBorder(Rectangle.TOP);
                rightFooter.setBorderColor(new Color(220, 226, 232));
                rightFooter.setBorderWidth(0.5f);
                rightFooter.setPaddingTop(3);

                footerTable.addCell(leftFooter);
                footerTable.addCell(rightFooter);
                footerTable.writeSelectedRows(0, -1, document.left(), document.bottom() - 8, writer.getDirectContent());
            } catch (Exception ignored) {}
        }
    }
}
