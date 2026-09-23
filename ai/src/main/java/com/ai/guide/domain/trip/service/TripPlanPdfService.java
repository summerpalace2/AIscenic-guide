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
            String sourceMode = text(plan.get("sourceMode"), "高德地图联动");
            writer.setPageEvent(new PageHeaderFooterEvent(baseFont, nowStr, sourceMode));

            document.open();

            // 刊物级调色板
            Color primaryColor = new Color(24, 76, 102);     // #184C66 山城江岸青
            Color primaryDark = new Color(15, 48, 65);       // #0F3041 封面主色
            Color accentColor = new Color(214, 90, 36);      // #D65A24 暮色晚霞橙
            Color darkText = new Color(30, 41, 59);          // #1E293B 标题深色
            Color bodyText = new Color(51, 65, 85);          // #334155 正文内容
            Color mutedText = new Color(100, 116, 139);      // #64748B 次要说明
            Color lightBg = new Color(248, 250, 252);        // #F8FAFC 极浅底色
            Color cardBorder = new Color(226, 232, 240);     // #E2E8F0 边框微色
            Color tagBlueText = new Color(29, 78, 216);      // #1D4ED8 景点标签深蓝
            Color linkColor = new Color(37, 99, 235);        // #2563EB 高德链接蓝

            // 美食专属暖色系统
            Color diningLeftBg = new Color(255, 247, 237);   // #FFF7ED 暖橙左底
            Color diningRightBg = new Color(255, 253, 248);  // #FFFDF8 暖白卡片底
            Color diningBorder = new Color(254, 215, 170);   // #FED7AA 暖橙细边框
            Color diningTitle = new Color(124, 45, 18);      // #7C2D12 暖褐红
            Color diningSpecial = new Color(154, 52, 18);    // #9A3412 招牌菜橙色
            Color diningTagColor = new Color(194, 65, 12);   // #C2410C 餐饮标签色

            // 字体定义
            Font eyebrowFont = new Font(baseFont, 8.5f, Font.BOLD, accentColor);
            Font titleFont = new Font(baseFont, 17f, Font.BOLD, primaryColor);
            Font subtitleFont = new Font(baseFont, 9f, Font.NORMAL, mutedText);
            Font dayBannerTitleFont = new Font(baseFont, 10.5f, Font.BOLD, Color.WHITE);
            Font dayBannerDateFont = new Font(baseFont, 8.5f, Font.NORMAL, new Color(241, 245, 249));
            Font cardNameFont = new Font(baseFont, 11.5f, Font.BOLD, darkText);
            Font diningNameFont = new Font(baseFont, 11.5f, Font.BOLD, diningTitle);
            Font diningSpecialFont = new Font(baseFont, 8.5f, Font.BOLD, diningSpecial);
            Font cardBodyFont = new Font(baseFont, 8.5f, Font.NORMAL, bodyText);
            Font cardMutedFont = new Font(baseFont, 8f, Font.NORMAL, mutedText);
            Font tagFont = new Font(baseFont, 7.5f, Font.BOLD, tagBlueText);
            Font tagDiningFont = new Font(baseFont, 7.5f, Font.BOLD, diningTagColor);
            Font linkFont = new Font(baseFont, 8f, Font.NORMAL, linkColor);
            Font diningLinkFont = new Font(baseFont, 8f, Font.NORMAL, diningTagColor);
            Font timeBigFont = new Font(baseFont, 12f, Font.BOLD, primaryColor);
            Font timeDiningFont = new Font(baseFont, 12f, Font.BOLD, accentColor);
            Font noticeTitleFont = new Font(baseFont, 9f, Font.BOLD, new Color(133, 100, 4));
            Font noticeBodyFont = new Font(baseFont, 8f, Font.NORMAL, new Color(100, 75, 5));

            // 行程数据提取
            String title = text(plan.get("title"), "重庆定制旅行手册");
            String subtitle = text(plan.get("subtitle"), "慢节奏 · 人文探索 · 山城地标专属定制路书");
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

            // 1. 顶部品牌与行程大标题
            Paragraph eyebrow = new Paragraph("悠 悠 智 策  ·  CHONGQING TAILORED TRAVEL HANDBOOK", eyebrowFont);
            eyebrow.setSpacingBefore(0);
            eyebrow.setSpacingAfter(2);
            document.add(eyebrow);

            Paragraph mainTitle = new Paragraph(title, titleFont);
            mainTitle.setSpacingAfter(3);
            document.add(mainTitle);

            // 清洗副标题中的“按约束排序”、“未提供”等字样
            String cleanSubtitle = subtitle;
            if (cleanSubtitle.contains("按约束排序") || cleanSubtitle.contains("未提供")) {
                cleanSubtitle = cleanSubtitle.replace("按约束排序", "")
                        .replace("· 未提供", "")
                        .replace("未提供 ·", "")
                        .replace("未提供", "")
                        .replaceAll("^[ ·\\-]+", "")
                        .replaceAll("[ ·\\-]+$", "")
                        .trim();
            }
            if (cleanSubtitle.isBlank()) {
                cleanSubtitle = "慢节奏 · 人文探索 · 山城地标专属定制路书";
            }
            Paragraph subTitlePara = new Paragraph(cleanSubtitle, subtitleFont);
            subTitlePara.setSpacingAfter(8);
            document.add(subTitlePara);

            // 2. 行程关键概览看板（精美网格卡片）
            PdfPTable overviewTable = new PdfPTable(4);
            overviewTable.setWidthPercentage(100);
            overviewTable.setWidths(new float[]{25f, 25f, 25f, 25f});
            overviewTable.setSpacingAfter(7);

            String durationText = days.isEmpty() ? "1 天" : days.size() + " 天 " + Math.max(0, days.size() - 1) + " 晚";
            String companionsText = displayOverviewValue(constraints.get("companions"), "自由行 / 默认推荐");
            String walkingText = displayOverviewValue(constraints.get("walkingTolerance"), "适度漫步");
            String transportText = displayOverviewValue(constraints.containsKey("transportPreference")
                    ? constraints.get("transportPreference") : planContext.get("routePreference"), "公共交通优先");
            String budgetText = displayOverviewValue(constraints.get("budget"), "经济舒适");
            String dietText = displayOverviewValue(constraints.get("dietPreference"), "地道特色推荐");
            String stayAreaText = displayOverviewValue(constraints.containsKey("stayArea")
                    ? constraints.get("stayArea") : planContext.get("startingArea"), "市中心 / 临江片区");

            overviewTable.addCell(createOverviewCell("游玩周期", durationText, baseFont, lightBg, cardBorder));
            overviewTable.addCell(createOverviewCell("精选站点", totalStops + " 处站点 (含用餐)", baseFont, lightBg, cardBorder));
            overviewTable.addCell(createOverviewCell("同行画像", companionsText, baseFont, lightBg, cardBorder));
            overviewTable.addCell(createOverviewCell("步行偏好", walkingText, baseFont, lightBg, cardBorder));
            overviewTable.addCell(createOverviewCell("出行方式", transportText, baseFont, lightBg, cardBorder));
            overviewTable.addCell(createOverviewCell("风味餐饮", dietText, baseFont, lightBg, cardBorder));
            overviewTable.addCell(createOverviewCell("预算策略", budgetText, baseFont, lightBg, cardBorder));
            overviewTable.addCell(createOverviewCell("出发参考", stayAreaText, baseFont, lightBg, cardBorder));
            document.add(overviewTable);

            // 3. 数据来源与行程版本记录条
            String tripMetadata = tripMetadata(plan);
            PdfPTable sourceTable = new PdfPTable(1);
            sourceTable.setWidthPercentage(100);
            sourceTable.setSpacingAfter(7);
            String verifyText = "🗺️  高德官方地图数据联动 · 实时核验推荐"
                    + (tripMetadata.isBlank() ? "" : "  |  " + tripMetadata);
            PdfPCell sourceCell = new PdfPCell(new Phrase(verifyText, cardMutedFont));
            sourceCell.setBackgroundColor(new Color(241, 245, 249));
            sourceCell.setBorderColor(new Color(203, 213, 225));
            sourceCell.setBorderWidth(0.5f);
            sourceCell.setPadding(5);
            sourceTable.addCell(sourceCell);
            document.add(sourceTable);

            // 餐饮策略引导（若有）
            String foodGuidance = text(planContext.get("foodGuidance"), "");
            if (foodGuidance.startsWith("饮食策略：")) foodGuidance = foodGuidance.substring("饮食策略：".length()).trim();
            if (foodGuidance.startsWith("餐饮策略：")) foodGuidance = foodGuidance.substring("餐饮策略：".length()).trim();
            if (!foodGuidance.isBlank() && !foodGuidance.contains("未指定")) {
                PdfPTable tipTable = new PdfPTable(1);
                tipTable.setWidthPercentage(100);
                tipTable.setSpacingAfter(7);
                PdfPCell cell = new PdfPCell(new Phrase("🍽️ 餐饮规划：结合景区周边精选地道特色美食，午晚两餐就近步行可达，免受奔波之苦。", cardBodyFont));
                cell.setBackgroundColor(new Color(255, 251, 240));
                cell.setBorderColor(new Color(246, 224, 134));
                cell.setBorderWidth(0.5f);
                cell.setPadding(5);
                tipTable.addCell(cell);
                document.add(tipTable);
            }

            // 4. 每日时间线与景点卡片
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

                // 每日 Banner（刊物杂志风格，实心深蓝高品质抬头）
                PdfPTable dayBanner = new PdfPTable(2);
                dayBanner.setWidthPercentage(100);
                dayBanner.setWidths(new float[]{70f, 30f});
                dayBanner.setSpacingBefore(9);
                dayBanner.setSpacingAfter(5);

                PdfPCell dayTitleCell = new PdfPCell(new Phrase("  DAY 0" + dayNumber + "  |  " + dateLabel, dayBannerTitleFont));
                dayTitleCell.setBackgroundColor(primaryDark);
                dayTitleCell.setBorder(Rectangle.NO_BORDER);
                dayTitleCell.setPaddingTop(5);
                dayTitleCell.setPaddingBottom(5);
                dayTitleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

                String wDisplay = weatherStr.isBlank() ? "🌤️ 出行前核验天气" : weatherStr;
                PdfPCell weatherCell = new PdfPCell(new Phrase(wDisplay + "  ", dayBannerDateFont));
                weatherCell.setBackgroundColor(primaryDark);
                weatherCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                weatherCell.setBorder(Rectangle.NO_BORDER);
                weatherCell.setPaddingTop(5);
                weatherCell.setPaddingBottom(5);
                weatherCell.setVerticalAlignment(Element.ALIGN_MIDDLE);

                dayBanner.addCell(dayTitleCell);
                dayBanner.addCell(weatherCell);
                document.add(dayBanner);

                // 出发衔接提示（精致左边框卡片）
                String departureContext = text(day.get("departureContext"), "");
                if (!departureContext.isBlank() && !departureContext.contains("未指定")) {
                    PdfPTable depTable = new PdfPTable(1);
                    depTable.setWidthPercentage(100);
                    depTable.setSpacingAfter(5);
                    PdfPCell depCell = new PdfPCell(new Phrase("📍 出发接驳：" + departureContext, cardBodyFont));
                    depCell.setBackgroundColor(new Color(254, 251, 240));
                    depCell.setBorder(Rectangle.LEFT);
                    depCell.setBorderColor(accentColor);
                    depCell.setBorderWidth(2.5f);
                    depCell.setPadding(5);
                    depTable.addCell(depCell);
                    document.add(depTable);
                }

                // 站点卡片列表
                List<?> stops = day.get("stops") instanceof List<?> list ? list : List.of();
                for (int i = 0; i < stops.size(); i++) {
                    Object stopObj = stops.get(i);
                    if (!(stopObj instanceof Map<?, ?> stop)) continue;

                    boolean isDining = isDiningStop(stop);

                    PdfPTable cardTable = new PdfPTable(2);
                    cardTable.setWidthPercentage(100);
                    cardTable.setWidths(new float[]{18f, 82f});
                    cardTable.setKeepTogether(true);
                    cardTable.setSpacingAfter(4);

                    // 左侧：时间、标签与时长
                    PdfPCell leftCell = new PdfPCell();
                    leftCell.setBackgroundColor(isDining ? diningLeftBg : new Color(241, 245, 249));
                    leftCell.setBorderColor(isDining ? diningBorder : cardBorder);
                    leftCell.setBorderWidth(0.6f);
                    leftCell.setPadding(6);
                    leftCell.setHorizontalAlignment(Element.ALIGN_CENTER);

                    String stopTime = displayValue(stop.containsKey("time") ? stop.get("time") : stop.get("startTime"));
                    String duration = displayValue(stop.get("duration"));

                    String displayTime = isDining ? (String.valueOf(stop.get("name")).contains("午餐") ? "午间就餐" : (String.valueOf(stop.get("name")).contains("晚餐") ? "晚间就餐" : "随游就餐")) : stopTime;
                    Paragraph timePara = new Paragraph(displayTime, isDining ? timeDiningFont : timeBigFont);
                    timePara.setAlignment(Element.ALIGN_CENTER);
                    leftCell.addElement(timePara);

                    String slotTag = isDining
                            ? (String.valueOf(stop.get("name")).contains("午餐") ? "🍽️ 午餐推荐" : (String.valueOf(stop.get("name")).contains("晚餐") ? "🥘 晚餐推荐" : "🥢 餐饮推荐"))
                            : "第 0" + (i + 1) + " 站";
                    Paragraph stopIndexPara = new Paragraph(slotTag, isDining ? tagDiningFont : tagFont);
                    stopIndexPara.setAlignment(Element.ALIGN_CENTER);
                    stopIndexPara.setSpacingBefore(1);
                    leftCell.addElement(stopIndexPara);

                    Paragraph durPara = new Paragraph(duration, cardMutedFont);
                    durPara.setAlignment(Element.ALIGN_CENTER);
                    durPara.setSpacingBefore(1);
                    leftCell.addElement(durPara);

                    // 右侧：景点/餐厅详情与指引
                    PdfPCell rightCell = new PdfPCell();
                    rightCell.setBackgroundColor(isDining ? diningRightBg : Color.WHITE);
                    rightCell.setBorderColor(isDining ? diningBorder : cardBorder);
                    rightCell.setBorderWidth(0.6f);
                    rightCell.setPadding(6);

                    String stopName = text(stop.get("name"), text(stop.get("displayName"), "未命名站点"));
                    String district = text(stop.get("district"), "");
                    String ticket = isDining ? text(stop.get("ticket"), text(stop.get("costSummary"), "")) : text(stop.get("ticket"), "");

                    Phrase namePhrase = new Phrase();
                    namePhrase.add(new Chunk(stopName, isDining ? diningNameFont : cardNameFont));
                    String metadata = joinMetadata(district, ticket);
                    if (!metadata.isBlank()) {
                        namePhrase.add(new Chunk("  [" + metadata + "]", isDining ? tagDiningFont : tagFont));
                    }
                    rightCell.addElement(new Paragraph(namePhrase));

                    // 美食卡片专属：招牌必吃高光行
                    String specialtyDish = text(stop.get("specialtyDish"), "");
                    if (isDining && !specialtyDish.isBlank()) {
                        Paragraph dishPara = new Paragraph("🥘 招牌必吃：" + specialtyDish, diningSpecialFont);
                        dishPara.setSpacingBefore(2);
                        dishPara.setSpacingAfter(2);
                        rightCell.addElement(dishPara);
                    }

                    String summary = text(stop.get("summary"), text(stop.get("detail"), ""));
                    if (!summary.isBlank() && !summary.equals(specialtyDish)) {
                        Paragraph summaryPara = new Paragraph(summary, cardBodyFont);
                        summaryPara.setSpacingBefore(1);
                        summaryPara.setSpacingAfter(2);
                        rightCell.addElement(summaryPara);
                    }

                    String reason = text(stop.get("recommendationReason"), "");
                    if (!reason.isBlank() && !reason.equals(summary)) {
                        Paragraph reasonPara = new Paragraph("💡 推荐理由：" + reason, cardMutedFont);
                        reasonPara.setSpacingAfter(2);
                        rightCell.addElement(reasonPara);
                    }

                    String routeText = routeText(stop, planContext);
                    if (!routeText.isBlank()) {
                        String cleanRoute = cleanRouteText(routeText);
                        Paragraph routePara = new Paragraph((isDining ? "🚶 步行到达：" : "🚶 到达方式：") + cleanRoute, cardMutedFont);
                        routePara.setSpacingAfter(2);
                        rightCell.addElement(routePara);
                    }

                    // 高德导航链接
                    String location = text(stop.get("location"), "");
                    String routeMode = routeMode(stop, planContext);
                    String navUrl = buildAmapNavUrl(stopName, location, routeMode);
                    Anchor navLink = new Anchor(hasCoordinates(location)
                            ? (isDining ? "🍴 在高德地图打开餐厅导航 →" : "📍 在高德地图打开目的地导航 →")
                            : "📍 在高德地图搜索该地点 →", isDining ? diningLinkFont : linkFont);
                    navLink.setReference(navUrl);
                    Paragraph linkPara = new Paragraph();
                    linkPara.add(navLink);
                    linkPara.setSpacingBefore(2);
                    rightCell.addElement(linkPara);

                    cardTable.addCell(leftCell);
                    cardTable.addCell(rightCell);
                    document.add(cardTable);

                    // 站点间过渡衔接（平滑过渡，不再重复错误字样）
                    if (i < stops.size() - 1 && stops.get(i + 1) instanceof Map<?, ?> nextStop) {
                        String transition = routeText(nextStop, planContext);
                        String transitionDisplay = cleanTransitionText(transition);
                        if (!transitionDisplay.isBlank()) {
                            PdfPTable transitTable = new PdfPTable(1);
                            transitTable.setWidthPercentage(100);
                            transitTable.setSpacingAfter(3);
                            PdfPCell transitCell = new PdfPCell(new Phrase("    ↓  下一程接驳：" + transitionDisplay, cardMutedFont));
                            transitCell.setBorder(Rectangle.NO_BORDER);
                            transitCell.setPadding(0);
                            transitTable.addCell(transitCell);
                            document.add(transitTable);
                        }
                    }
                }
            }

            // 5. 出行贴士与动态核验说明
            PdfPTable noticeTable = new PdfPTable(1);
            noticeTable.setWidthPercentage(100);
            noticeTable.setKeepTogether(true);
            noticeTable.setSpacingBefore(10);
            noticeTable.setSpacingAfter(6);

            PdfPCell noticeCell = new PdfPCell();
            noticeCell.setBackgroundColor(new Color(254, 251, 240));
            noticeCell.setBorderColor(new Color(246, 224, 134));
            noticeCell.setBorderWidth(0.8f);
            noticeCell.setPadding(8);

            Paragraph noticeTitle = new Paragraph("📋 悠悠智策 · 出行实用指南与动态贴士", noticeTitleFont);
            noticeTitle.setSpacingAfter(3);
            noticeCell.addElement(noticeTitle);

            noticeCell.addElement(new Paragraph("• 门票、开放时间、预约、交通和天气等动态信息可能变化，出发前请再次核验景区官方公告或高德实时结果。", noticeBodyFont));
            noticeCell.addElement(new Paragraph("• 行程中的路线摘要结合高德实时路网规划；点击卡片可一键唤起官方地图精准导航。", noticeBodyFont));
            noticeCell.addElement(new Paragraph("• 美食推荐已精选各游览片区地道老字号与招牌菜，午晚餐就近步行可达，出行前可致电确认包间或排队情况。", noticeBodyFont));

            noticeTable.addCell(noticeCell);
            document.add(noticeTable);

            document.close();
            return output.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("行程 PDF 生成失败", e);
        }
    }

    private static boolean isDiningStop(Map<?, ?> stop) {
        if (stop == null) return false;
        String type = stop.get("type") != null ? String.valueOf(stop.get("type")) : "";
        String category = stop.get("category") != null ? String.valueOf(stop.get("category")) : "";
        String icon = stop.get("icon") != null ? String.valueOf(stop.get("icon")) : "";
        String name = stop.get("name") != null ? String.valueOf(stop.get("name")) : "";
        String id = stop.get("id") != null ? String.valueOf(stop.get("id")) : "";
        return "DINING".equalsIgnoreCase(type)
                || "美食".equals(category)
                || "餐".equals(icon)
                || id.contains("dining")
                || name.contains("餐推荐")
                || name.contains("【早餐】")
                || name.contains("【午餐】")
                || name.contains("【晚餐】");
    }

    private static PdfPCell createOverviewCell(String label, String value, BaseFont font, Color bg, Color border) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(bg);
        cell.setBorderColor(border);
        cell.setBorderWidth(0.5f);
        cell.setPadding(5);
        Font labelFont = new Font(font, 7.5f, Font.NORMAL, new Color(100, 116, 139));
        Font valFont = new Font(font, 8.5f, Font.BOLD, new Color(30, 41, 59));
        cell.addElement(new Paragraph(label, labelFont));
        Paragraph valPara = new Paragraph(value, valFont);
        valPara.setSpacingBefore(1);
        cell.addElement(valPara);
        return cell;
    }

    private static String displayOverviewValue(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank() || "未提供".equals(String.valueOf(value)) || "未指定".equals(String.valueOf(value))) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static String cleanRouteText(String routeText) {
        if (routeText == null || routeText.isBlank() || routeText.contains("路线待核验")) {
            return "建议市内公共交通或打车便捷直达（可在高德一键规划）";
        }
        return routeText;
    }

    private static String cleanTransitionText(String transition) {
        if (transition == null || transition.isBlank() || transition.contains("路线待核验")) {
            return "顺路公共交通或打车接驳 · 预计 10-15 分钟";
        }
        return transition;
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
