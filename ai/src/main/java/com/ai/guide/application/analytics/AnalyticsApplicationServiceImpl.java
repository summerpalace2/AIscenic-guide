package com.ai.guide.application.analytics;

import com.ai.guide.domain.analytics.service.AnalyticsService;
import com.ai.guide.domain.analytics.service.OnlinePresenceService;
import com.ai.guide.domain.analytics.service.PdfExportService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 运营监控与服务看板应用服务默认实现类 (Analytics Application Service Implementation)
 *
 * <p>所属架构层级：COLA 4.0 Application Layer（应用层）</p>
 * <p>核心职责：统筹调度领域层统计分析服务 {@link AnalyticsService}、报表导出服务 {@link PdfExportService}
 * 以及在线状态感知服务 {@link OnlinePresenceService}，封装日期标准化提取、跨服务结果规整等业务用例。</p>
 */
@Service
public class AnalyticsApplicationServiceImpl implements AnalyticsApplicationService {

    private final AnalyticsService analyticsService;
    private final PdfExportService pdfExportService;
    private final OnlinePresenceService onlinePresenceService;

    public AnalyticsApplicationServiceImpl(AnalyticsService analyticsService,
                                          PdfExportService pdfExportService,
                                          OnlinePresenceService onlinePresenceService) {
        this.analyticsService = analyticsService;
        this.pdfExportService = pdfExportService;
        this.onlinePresenceService = onlinePresenceService;
    }

    @Override
    public Map<String, Object> getDashboard(String period) {
        return analyticsService.dashboard(period);
    }

    @Override
    public List<Map<String, Object>> getHotQuestions(String period, int topN) {
        return analyticsService.hotQuestions(period, topN);
    }

    @Override
    public List<Map<String, Object>> getSentimentTrend(String startDate, String endDate, String granularity) {
        return analyticsService.sentimentTrend(startDate, endDate, granularity);
    }

    @Override
    public List<Map<String, Object>> getServiceCount(String startDate, String endDate, String granularity) {
        return analyticsService.serviceCount(startDate, endDate, granularity);
    }

    @Override
    public Map<String, Object> listReports(int page, int size) {
        return analyticsService.listReports(page, size);
    }

    @Override
    public Map<String, Object> getReport(String reportId) {
        return analyticsService.getReport(reportId);
    }

    @Override
    public String resolveReportDate(Map<String, Object> report) {
        if (report == null) return null;
        String date = (String) report.get("period_end");
        if (date == null || date.isEmpty()) date = (String) report.get("periodStart");
        if (date != null && date.length() >= 10) date = date.substring(0, 10);
        return date;
    }

    @Override
    public ReportExportResult exportReportPdf(String reportId) {
        Map<String, Object> report = analyticsService.getReport(reportId);
        if (report == null) return null;
        String date = resolveReportDate(report);
        byte[] pdf = pdfExportService.exportDailyReport(date);
        return new ReportExportResult(pdf, date != null ? date : reportId);
    }

    @Override
    public byte[] exportTodayReportPdf() {
        return pdfExportService.exportDailyReport(LocalDate.now().toString());
    }

    @Override
    public void heartbeat() {
        onlinePresenceService.heartbeat();
    }

    @Override
    public List<Map<String, Object>> listOnlineUsers() {
        return onlinePresenceService.listOnlineUsers();
    }
}
