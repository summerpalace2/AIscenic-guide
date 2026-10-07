package com.ai.guide.adapter.analytics.web;

import com.ai.guide.application.analytics.AnalyticsApplicationService;
import com.ai.guide.common.model.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 文旅指标数据大盘与统计分析控制器 (Analytics Controller)
 *
 * <p>所属架构层级：COLA 4.0 Adapter Layer (适配层 Web 控制器)</p>
 * <p>架构职责：为前端/管理端提供文旅智能导游服务指标大盘、服务量时序分布、游客情感分析趋势、热点咨询排行以及运营日报/周报导出能力。</p>
 */
@RestController
@RequestMapping("/ai/analytics")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsApplicationService analyticsApplicationService;

    public AnalyticsController(AnalyticsApplicationService analyticsApplicationService) {
        this.analyticsApplicationService = analyticsApplicationService;
    }

    /**
     * Dashboard 综合统计大屏数据
     *
     * @param period 统计周期范围（默认 "today"；可选 "week"、"month"、"all"）
     * @return 包含各项核心 KPI 的 Result 包装结构
     */
    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard(
            @RequestParam(value = "period", defaultValue = "today") String period) {
        try {
            Map<String, Object> data = analyticsApplicationService.getDashboard(period);
            return Result.success("查询成功", data);
        } catch (IllegalArgumentException e) {
            return Result.error(400,e.getMessage());
        } catch (Exception e) {
            log.error("[Analytics] dashboard 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 热门问题与咨询聚类排行
     *
     * @param period 统计时间周期（默认 "today"）
     * @param topN   最大返回条目数（默认 10）
     * @return 高频热点问题排行列表
     */
    @GetMapping("/hot-questions")
    public Result<List<Map<String, Object>>> hotQuestions(
            @RequestParam(value = "period", defaultValue = "today") String period,
            @RequestParam(value = "topN", defaultValue = "10") int topN) {
        try {
            List<Map<String, Object>> data = analyticsApplicationService.getHotQuestions(period, topN);
            return Result.success("查询成功", data);
        } catch (IllegalArgumentException e) {
            return Result.error(400,e.getMessage());
        } catch (Exception e) {
            log.error("[Analytics] hot-questions 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 游客情绪极性时序演变趋势
     *
     * @param startDate   统计起始日期 (yyyy-MM-dd)，可选
     * @param endDate     统计截止日期 (yyyy-MM-dd)，可选
     * @param granularity 时间粒度（默认 "day"）
     * @return 情绪时序分布点序列
     */
    @GetMapping("/sentiment-trend")
    public Result<List<Map<String, Object>>> sentimentTrend(
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "granularity", defaultValue = "day") String granularity) {
        try {
            List<Map<String, Object>> data = analyticsApplicationService.getSentimentTrend(startDate, endDate, granularity);
            return Result.success("查询成功", data);
        } catch (IllegalArgumentException e) {
            return Result.error(400,e.getMessage());
        } catch (Exception e) {
            log.error("[Analytics] sentiment-trend 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 导游服务调用量时序统计
     *
     * @param startDate   统计起始日期 (yyyy-MM-dd)，可选
     * @param endDate     统计截止日期 (yyyy-MM-dd)，可选
     * @param granularity 时间粒度（默认 "day"）
     * @return 服务量时序聚合列表
     */
    @GetMapping("/service-count")
    public Result<List<Map<String, Object>>> serviceCount(
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "granularity", defaultValue = "day") String granularity) {
        try {
            List<Map<String, Object>> data = analyticsApplicationService.getServiceCount(startDate, endDate, granularity);
            return Result.success("查询成功", data);
        } catch (IllegalArgumentException e) {
            return Result.error(400,e.getMessage());
        } catch (Exception e) {
            log.error("[Analytics] service-count 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 分页查询运营总结报告列表
     *
     * @param page 分页页码（默认 1）
     * @param size 每页大小（默认 20）
     * @return 报告列表与分页数据
     */
    @GetMapping("/reports")
    public Result<Map<String, Object>> listReports(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        try {
            Map<String, Object> data = analyticsApplicationService.listReports(page, size);
            return Result.success("查询成功", data);
        } catch (IllegalArgumentException e) {
            return Result.error(400,e.getMessage());
        } catch (Exception e) {
            log.error("[Analytics] reports 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 查询指定运营报告详细内容
     *
     * @param reportId 运营报告主键 ID
     * @return 报告详情；不存在返回 404 "报告不存在"
     */
    @GetMapping("/reports/{reportId}")
    public Result<Map<String, Object>> getReport(@PathVariable String reportId) {
        try {
            Map<String, Object> data = analyticsApplicationService.getReport(reportId);
            if (data == null) return Result.error(404, "报告不存在");
            return Result.success("查询成功", data);
        } catch (IllegalArgumentException e) {
            return Result.error(400,e.getMessage());
        } catch (Exception e) {
            log.error("[Analytics] report detail 失败: {}", e.getMessage());
            return Result.error(500, "查询失败，请稍后重试");
        }
    }

    /**
     * 导出指定历史运营报告 PDF 文件流
     *
     * @param reportId 运营报告 ID
     * @return PDF 二进制文件流，附带 Content-Disposition 附件下载头；报告不存在返回 404；失败返回 500
     */
    @GetMapping("/reports/{reportId}/export")
    public ResponseEntity<byte[]> exportReportPdf(@PathVariable String reportId) {
        try {
            com.ai.guide.application.analytics.ReportExportResult result = analyticsApplicationService.exportReportPdf(reportId);
            if (result == null) {
                return ResponseEntity.notFound().build();
            }
            byte[] pdf = result.pdfData();
            if (pdf == null) {
                return ResponseEntity.internalServerError().body("PDF 生成失败".getBytes());
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "report_" + result.fileName() + ".pdf");
            headers.setContentLength(pdf.length);
            return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("[Analytics] PDF 导出失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("导出失败，请稍后重试".getBytes());
        }
    }

    /**
     * 导出今日日报 PDF（根据当天实时数据动态渲染）
     *
     * @return PDF 二进制文件流，附带 Content-Disposition: attachment; filename=daily_report_<date>.pdf
     */
    @GetMapping("/export/today")
    public ResponseEntity<byte[]> exportToday() {
        try {
            byte[] pdf = analyticsApplicationService.exportTodayReportPdf();
            if (pdf == null) {
                return ResponseEntity.internalServerError().body("PDF 生成失败".getBytes());
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentDispositionFormData("attachment", "daily_report_" + LocalDate.now() + ".pdf");
            headers.setContentLength(pdf.length);
            return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("[Analytics] 昨日日报导出失败: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("导出失败，请稍后重试".getBytes());
        }
    }
}
