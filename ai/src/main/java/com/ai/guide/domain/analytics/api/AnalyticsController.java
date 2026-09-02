package com.ai.guide.domain.analytics.api;

import com.ai.guide.common.model.Result;
import com.ai.guide.domain.analytics.service.AnalyticsService;
import com.ai.guide.domain.analytics.service.PdfExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/**
 * 运营数据分析与统计控制器 (Analytics Controller)
 *
 * 所属领域：domain.analytics (运营监控与服务看板域)
 * 架构职责：为前端/管理端提供文旅智能导游服务指标大盘、服务量时序分布、游客情感分析趋势、热点咨询排行以及运营日报/周报导出能力。
 *
 * 核心对外接口与关键方法：
 * 1. {@link #dashboard}: 获取大屏综合统计数据（服务总人次、好评率、平均响应时延、当前热门景点等）。
 *    - 关键参数：period (统计周期：today=今日 / week=本周 / month=本月 / all=全量)。
 *    - 返回结果：包含核心 KPI 指标的结构化数据 Map。
 * 2. {@link #hotQuestions}: 查询游客高频咨询热点问题与关键词聚类排行。
 *    - 关键参数：limit (返回前 N 条), period (时间周期)。
 * 3. {@link #sentimentTrend}: 游客情绪倾向趋势时序数据（积极/中性/消极占比）。
 *    - 关键参数：days (回溯统计天数)。
 * 4. {@link #exportTodayReport}: 导出当期运营日报/总结报告 PDF 文件流。
 *    - 返回结果：application/pdf 媒体类型的二进制数据流。
 */
@RestController
@RequestMapping("/ai/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsService analyticsService;
    private final PdfExportService pdfExportService;

    public AnalyticsController(AnalyticsService analyticsService, PdfExportService pdfExportService) {
        this.analyticsService = analyticsService;
        this.pdfExportService = pdfExportService;
    }

    /**
     * Dashboard 综合统计
     */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(
            @RequestParam(value = "period", defaultValue = "today") String period) {
        try {
            Map<String, Object> data = analyticsService.dashboard(period);
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Analytics] dashboard 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 热门问题排行
     */
    @GetMapping("/hot-questions")
    public Result<List<Map<String, Object>>> hotQuestions(
            @RequestParam(value = "period", defaultValue = "today") String period,
            @RequestParam(value = "topN", defaultValue = "10") int topN) {
        try {
            List<Map<String, Object>> data = analyticsService.hotQuestions(period, topN);
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Analytics] hot-questions 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 情绪趋势
     */
    @GetMapping("/sentiment-trend")
    public Result<List<Map<String, Object>>> sentimentTrend(
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "granularity", defaultValue = "day") String granularity) {
        try {
            List<Map<String, Object>> data = analyticsService.sentimentTrend(startDate, endDate, granularity);
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Analytics] sentiment-trend 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 服务量时序统计
     */
    @GetMapping("/service-count")
    public Result<List<Map<String, Object>>> serviceCount(
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "granularity", defaultValue = "day") String granularity) {
        try {
            List<Map<String, Object>> data = analyticsService.serviceCount(startDate, endDate, granularity);
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Analytics] service-count 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 报告列表
     */
    @GetMapping("/reports")
    public Result<Map<String, Object>> listReports(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        try {
            Map<String, Object> data = analyticsService.listReports(page, size);
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Analytics] reports 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 报告详情
     */
    @GetMapping("/reports/{reportId}")
    public Result<Map<String, Object>> getReport(@PathVariable String reportId) {
        try {
            Map<String, Object> data = analyticsService.getReport(reportId);
            if (data == null) return Result.error(404, "报告不存在");
            return Result.success("查询成功", data);
        } catch (Exception e) {
            log.error("[Analytics] report detail 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 导出报告 PDF
     * 对应 Python: GET /analytics/reports/{report_id}/export
     */
    @GetMapping("/reports/{reportId}/export")
    public ResponseEntity<byte[]> exportReportPdf(@PathVariable String reportId) {
        try {
            Map<String, Object> report = analyticsService.getReport(reportId);
            if (report == null) {
                return ResponseEntity.notFound().build();
            }
            String date = (String) report.get("period_end");
            if (date == null || date.isEmpty()) date = (String) report.get("periodStart");
            if (date != null && date.length() >= 10) date = date.substring(0, 10);

            byte[] pdf = pdfExportService.exportDailyReport(date);
            if (pdf == null) {
                return ResponseEntity.internalServerError().body("PDF 生成失败".getBytes());
            }

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "report_" + (date != null ? date : reportId) + ".pdf");
            headers.setContentLength(pdf.length);
            return new ResponseEntity<>(pdf, headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            log.error("[Analytics] PDF 导出失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("导出失败，请稍后重试".getBytes());
        }
    }

    /**
     * 导出今日日报 PDF（当天实时数据）
     */
    @GetMapping("/export/today")
    public ResponseEntity<byte[]> exportToday() {
        try {
            byte[] pdf = pdfExportService.exportDailyReport(java.time.LocalDate.now().toString());
            if (pdf == null) {
                return ResponseEntity.internalServerError().body("PDF 生成失败".getBytes());
            }
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "daily_report_" + java.time.LocalDate.now() + ".pdf");
            headers.setContentLength(pdf.length);
            return new ResponseEntity<>(pdf, headers, org.springframework.http.HttpStatus.OK);
        } catch (Exception e) {
            log.error("[Analytics] 昨日日报导出失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("导出失败，请稍后重试".getBytes());
        }
    }
}
