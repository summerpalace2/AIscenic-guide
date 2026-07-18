package com.ai.guide.controller;

import com.ai.guide.model.Result;
import com.ai.guide.service.AnalyticsService;
import com.ai.guide.service.PdfExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 数据分析控制器
 * 对应 Python 版本: backend/app/api/v1/analytics.py
 *
 * 原始 Python API 由 sleepearlyplease 创建
 * Java 转化版本由 summerpalace2 实现
 *
 * 接口列表：
 * GET /analytics/dashboard       — 综合服务统计面板
 * GET /analytics/hot-questions   — 热门问题排行
 * GET /analytics/sentiment-trend — 情绪趋势
 * GET /analytics/service-count   — 服务量时序
 * GET /analytics/reports         — 报告列表
 * GET /analytics/reports/{id}    — 报告详情
 * GET /analytics/reports/{id}/export — 导出 PDF
 * GET /analytics/export/yesterday   — 快捷导出昨日 PDF
 */
@CrossOrigin(origins = "*")
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
            return Result.error(500, "查询失败: " + e.getMessage());
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
            return Result.error(500, "查询失败: " + e.getMessage());
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
            return Result.error(500, "查询失败: " + e.getMessage());
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
            return Result.error(500, "查询失败: " + e.getMessage());
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
            return Result.error(500, "查询失败: " + e.getMessage());
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
            return Result.error(500, "查询失败: " + e.getMessage());
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
            return ResponseEntity.internalServerError().body(("导出失败: " + e.getMessage()).getBytes());
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
            return ResponseEntity.internalServerError().body(("导出失败: " + e.getMessage()).getBytes());
        }
    }
}
