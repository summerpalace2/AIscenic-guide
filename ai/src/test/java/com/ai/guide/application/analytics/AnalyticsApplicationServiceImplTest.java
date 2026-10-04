package com.ai.guide.application.analytics;

import com.ai.guide.domain.analytics.service.AnalyticsService;
import com.ai.guide.domain.analytics.service.OnlinePresenceService;
import com.ai.guide.domain.analytics.service.PdfExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsApplicationServiceImplTest {

    private AnalyticsService analyticsService;
    private PdfExportService pdfExportService;
    private OnlinePresenceService onlinePresenceService;
    private AnalyticsApplicationServiceImpl applicationService;

    @BeforeEach
    void setUp() {
        analyticsService = Mockito.mock(AnalyticsService.class);
        pdfExportService = Mockito.mock(PdfExportService.class);
        onlinePresenceService = Mockito.mock(OnlinePresenceService.class);
        applicationService = new AnalyticsApplicationServiceImpl(analyticsService, pdfExportService, onlinePresenceService);
    }

    @Test
    void testGetDashboard() {
        when(analyticsService.dashboard("today")).thenReturn(Map.of("kpi", 1));
        Map<String, Object> res = applicationService.getDashboard("today");
        assertEquals(1, res.get("kpi"));
        verify(analyticsService).dashboard("today");
    }

    @Test
    void testGetHotQuestions() {
        when(analyticsService.hotQuestions("today", 5)).thenReturn(List.of());
        List<Map<String, Object>> res = applicationService.getHotQuestions("today", 5);
        assertNotNull(res);
        verify(analyticsService).hotQuestions("today", 5);
    }

    @Test
    void testGetSentimentTrend() {
        when(analyticsService.sentimentTrend("2026-01-01", "2026-01-02", "day")).thenReturn(List.of());
        List<Map<String, Object>> res = applicationService.getSentimentTrend("2026-01-01", "2026-01-02", "day");
        assertNotNull(res);
        verify(analyticsService).sentimentTrend("2026-01-01", "2026-01-02", "day");
    }

    @Test
    void testGetServiceCount() {
        when(analyticsService.serviceCount("2026-01-01", "2026-01-02", "day")).thenReturn(List.of());
        List<Map<String, Object>> res = applicationService.getServiceCount("2026-01-01", "2026-01-02", "day");
        assertNotNull(res);
        verify(analyticsService).serviceCount("2026-01-01", "2026-01-02", "day");
    }

    @Test
    void testListReports() {
        when(analyticsService.listReports(1, 10)).thenReturn(Map.of("total", 0));
        Map<String, Object> res = applicationService.listReports(1, 10);
        assertEquals(0, res.get("total"));
        verify(analyticsService).listReports(1, 10);
    }

    @Test
    void testGetReport() {
        when(analyticsService.getReport("r1")).thenReturn(Map.of("id", "r1"));
        Map<String, Object> res = applicationService.getReport("r1");
        assertEquals("r1", res.get("id"));
        verify(analyticsService).getReport("r1");
    }

    @Test
    void testResolveReportDate() {
        assertNull(applicationService.resolveReportDate(null));
        assertEquals("2026-10-04", applicationService.resolveReportDate(Map.of("period_end", "2026-10-04T12:00:00")));
        assertEquals("2026-10-03", applicationService.resolveReportDate(Map.of("periodStart", "2026-10-03T12:00:00")));
        assertEquals("short", applicationService.resolveReportDate(Map.of("periodStart", "short")));
    }

    @Test
    void testExportReportPdf() {
        when(analyticsService.getReport("r-none")).thenReturn(null);
        assertNull(applicationService.exportReportPdf("r-none"));

        when(analyticsService.getReport("r1")).thenReturn(Map.of("period_end", "2026-10-04 00:00:00"));
        byte[] bytes = new byte[]{1, 2, 3};
        when(pdfExportService.exportDailyReport("2026-10-04")).thenReturn(bytes);
        ReportExportResult result = applicationService.exportReportPdf("r1");
        assertNotNull(result);
        assertArrayEquals(bytes, result.pdfData());
        assertEquals("2026-10-04", result.fileName());
    }

    @Test
    void testExportTodayReportPdf() {
        byte[] bytes = new byte[]{4, 5, 6};
        when(pdfExportService.exportDailyReport(LocalDate.now().toString())).thenReturn(bytes);
        assertArrayEquals(bytes, applicationService.exportTodayReportPdf());
    }

    @Test
    void testHeartbeatAndOnlineUsers() {
        applicationService.heartbeat();
        verify(onlinePresenceService).heartbeat();

        when(onlinePresenceService.listOnlineUsers()).thenReturn(List.of());
        List<Map<String, Object>> users = applicationService.listOnlineUsers();
        assertNotNull(users);
        verify(onlinePresenceService).listOnlineUsers();
    }
}
