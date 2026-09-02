package com.ai.guide.domain.trip.service;


import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TripPlanPdfServiceTest {

    private final TripPlanPdfService service = new TripPlanPdfService();

    @Test
    void exportsProfessionalTravelHandbookPdf() throws Exception {
        Map<String, Object> plan = Map.of(
                "title", "重庆2天一夜·山城漫游专属方案",
                "subtitle", "少走路优先 · 带父母 · 城市地标 + 山城夜景",
                "sourceMode", "高德动态路线",
                "sourceStatus", Map.of("dataCoverage", "完整", "fallback", false),
                "currentVersion", 3,
                "tripUpdatedAt", 1787100000000L,
                "constraints", Map.of(
                        "durationDays", 2,
                        "companions", "带父母",
                        "walkingTolerance", "少走路",
                        "transportPreference", "公共交通优先",
                        "budget", "有限",
                        "dietPreference", "本地菜优先",
                        "stayArea", "解放碑"
                ),
                "planContext", Map.of(
                        "startingArea", "解放碑",
                        "routePreference", "transit",
                        "foodGuidance", "饮食策略：优先安排重庆本地菜与老火锅体验，具体店铺与消费出发前确认。"
                ),
                "days", List.of(
                        Map.of(
                                "day", 1,
                                "dateLabel", "第1天 · 母城地标与山城夜景",
                                "date", "2026-08-22",
                                "departureContext", "已将“解放碑”作为出发衔接参考，具体酒店地址仍需高德解析。",
                                "weather", Map.of("value", "多云 24~31℃", "status", "动态"),
                                "stops", List.of(
                                        Map.of(
                                                "name", "解放碑步行街",
                                                "district", "渝中区",
                                                "time", "14:30",
                                                "duration", "约 90 分钟",
                                                "summary", "城市地标与街区热身，平街漫步把节奏放慢。",
                                                "recommendationReason", "适合作为抵渝第一站，地面平整无台阶对长辈友好。",
                                                "walk", "轨道2号线临江门站平街直达",
                                                "ticket", "免费开放",
                                                "location", "106.577054,29.557174"
                                        ),
                                        Map.of(
                                                "name", "洪崖洞民俗风貌区",
                                                "district", "渝中区",
                                                "time", "19:30",
                                                "duration", "约 90 分钟",
                                                "summary", "夜景主场，金碧辉煌吊脚楼依山就势。",
                                                "recommendationReason", "建议从11层沧白路进入乘直梯下行，避免徒步爬坡。",
                                                "walk", "步行约500米或乘直梯",
                                                "routeFromPrevious", Map.of(
                                                        "selectedMode", "公共交通",
                                                        "selected", Map.of(
                                                                "status", "动态",
                                                                "distanceMeters", 850,
                                                                "durationSeconds", 720
                                                        )
                                                ),
                                                "ticket", "免费开放",
                                                "location", "106.582455,29.563009"
                                        )
                                )
                        ),
                        Map.of(
                                "day", 2,
                                "dateLabel", "第2天 · 文博静谧与8D穿楼奇观",
                                "date", "2026-08-23",
                                "weather", Map.of("value", "阴转阵雨 23~29℃", "status", "动态"),
                                "stops", List.of(
                                        Map.of(
                                                "name", "重庆中国三峡博物馆",
                                                "district", "渝中区",
                                                "time", "10:00",
                                                "duration", "约 120 分钟",
                                                "summary", "全馆无障碍平滑观展，壮丽三峡与巴渝历史史诗。",
                                                "recommendationReason", "零台阶全无障碍环境，提供免费轮椅租借与歇脚座椅。",
                                                "walk", "轨道10号线曾家岩站直达",
                                                "ticket", "免费免预约（刷身份证）",
                                                "location", "106.549233,29.562788"
                                        ),
                                        Map.of(
                                                "name", "李子坝单轨穿楼观景平台",
                                                "district", "渝中区",
                                                "time", "14:30",
                                                "duration", "约 45 分钟",
                                                "summary", "单轨穿楼地面平坦观景台，捕捉山城8D魔幻奇观。",
                                                "recommendationReason", "地面观景广场平坦无台阶，停留时间短、识别度极高。",
                                                "walk", "轨道2号线李子坝站直达",
                                                "ticket", "免费开放",
                                                "location", "106.533221,29.553945"
                                        ),
                                        Map.of(
                                                "name", "重庆大剧院江岸夜景",
                                                "district", "江北区",
                                                "time", "19:30",
                                                "duration", "约 90 分钟",
                                                "summary", "隔江远眺千厮门与洪崖洞全景，江风拂面且免受人潮拥挤。",
                                                "recommendationReason", "开阔平坦广场机位，适合带父母少走路看全景。",
                                                "walk", "轨道6号线大剧院站出站即达",
                                                "ticket", "广场免费开放",
                                                "location", "106.578685,29.568449"
                                        )
                                )
                        )
                )
        );

        byte[] pdfBytes = service.export(plan);
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 5000, "PDF 字节大小应大于 5KB");

        // 验证 PDF 文件魔数 %PDF-
        assertEquals((byte) '%', pdfBytes[0]);
        assertEquals((byte) 'P', pdfBytes[1]);
        assertEquals((byte) 'D', pdfBytes[2]);
        assertEquals((byte) 'F', pdfBytes[3]);

        // 保存一份真实测试样张到 target 目录便于人工检视
        File outDir = new File("target/test-output");
        outDir.mkdirs();
        File sampleFile = new File(outDir, "yuyouzhice-handbook-sample.pdf");
        try (FileOutputStream fos = new FileOutputStream(sampleFile)) {
            fos.write(pdfBytes);
        }
        assertTrue(sampleFile.exists());
        assertTrue(sampleFile.length() > 0);
    }
}
