package com.ai.guide.adapter.analytics.web;

import com.ai.guide.application.analytics.AnalyticsApplicationService;
import com.ai.guide.common.model.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalyticsControllerTest {

    private AnalyticsApplicationService applicationService;
    private AnalyticsController controller;

    @BeforeEach
    void setUp() {
        applicationService = Mockito.mock(AnalyticsApplicationService.class);
        controller = new AnalyticsController(applicationService);
    }

    @Test
    void testDashboard_Success() {
        when(applicationService.getDashboard("today")).thenReturn(Map.of("totalUsers", 100));
        Result<Map<String, Object>> result = controller.dashboard("today");
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
        assertEquals(100, result.getData().get("totalUsers"));
    }

    @Test
    void testDashboard_Exception() {
        when(applicationService.getDashboard("today")).thenThrow(new RuntimeException("DB error"));
        Result<Map<String, Object>> result = controller.dashboard("today");
        assertEquals(500, result.getCode());
        assertEquals("查询失败，请稍后重试", result.getMessage());
    }

    @Test
    void testHotQuestions_Success() {
        when(applicationService.getHotQuestions("today", 10)).thenReturn(List.of(Map.of("q", "火锅哪家强")));
        Result<List<Map<String, Object>>> result = controller.hotQuestions("today", 10);
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
        assertEquals(1, result.getData().size());
    }

    @Test
    void testHotQuestions_Exception() {
        when(applicationService.getHotQuestions(anyString(), anyInt())).thenThrow(new RuntimeException("error"));
        Result<List<Map<String, Object>>> result = controller.hotQuestions("today", 10);
        assertEquals(500, result.getCode());
        assertEquals("查询失败，请稍后重试", result.getMessage());
    }

    @Test
    void testSentimentTrend_Success() {
        when(applicationService.getSentimentTrend("2026-10-01", "2026-10-04", "day")).thenReturn(List.of());
        Result<List<Map<String, Object>>> result = controller.sentimentTrend("2026-10-01", "2026-10-04", "day");
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
    }

    @Test
    void testSentimentTrend_Exception() {
        when(applicationService.getSentimentTrend(any(), any(), any())).thenThrow(new RuntimeException("error"));
        Result<List<Map<String, Object>>> result = controller.sentimentTrend("2026-10-01", "2026-10-04", "day");
        assertEquals(500, result.getCode());
        assertEquals("查询失败，请稍后重试", result.getMessage());
    }

    @Test
    void testServiceCount_Success() {
        when(applicationService.getServiceCount(any(), any(), any())).thenReturn(List.of(Map.of("count", 50)));
        Result<List<Map<String, Object>>> result = controller.serviceCount("2026-10-01", "2026-10-04", "day");
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
    }

    @Test
    void testServiceCount_Exception() {
        when(applicationService.getServiceCount(any(), any(), any())).thenThrow(new RuntimeException("error"));
        Result<List<Map<String, Object>>> result = controller.serviceCount(null, null, "day");
        assertEquals(500, result.getCode());
        assertEquals("查询失败，请稍后重试", result.getMessage());
    }

    @Test
    void testListReports_Success() {
        when(applicationService.listReports(1, 20)).thenReturn(Map.of("total", 5));
        Result<Map<String, Object>> result = controller.listReports(1, 20);
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
    }

    @Test
    void testListReports_Exception() {
        when(applicationService.listReports(anyInt(), anyInt())).thenThrow(new RuntimeException("error"));
        Result<Map<String, Object>> result = controller.listReports(1, 20);
        assertEquals(500, result.getCode());
        assertEquals("查询失败，请稍后重试", result.getMessage());
    }

    @Test
    void testGetReport_Success() {
        when(applicationService.getReport("rep-1")).thenReturn(Map.of("id", "rep-1"));
        Result<Map<String, Object>> result = controller.getReport("rep-1");
        assertEquals(200, result.getCode());
        assertEquals("查询成功", result.getMessage());
    }

    @Test
    void testGetReport_NotFound() {
        when(applicationService.getReport("rep-not-found")).thenReturn(null);
        Result<Map<String, Object>> result = controller.getReport("rep-not-found");
        assertEquals(404, result.getCode());
        assertEquals("报告不存在", result.getMessage());
    }

    @Test
    void testGetReport_Exception() {
        when(applicationService.getReport(anyString())).thenThrow(new RuntimeException("error"));
        Result<Map<String, Object>> result = controller.getReport("rep-err");
        assertEquals(500, result.getCode());
        assertEquals("查询失败，请稍后重试", result.getMessage());
    }

    @Test
    void testExportReportPdf_NotFound() {
        when(applicationService.exportReportPdf("rep-none")).thenReturn(null);
        ResponseEntity<byte[]> response = controller.exportReportPdf("rep-none");
        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void testExportReportPdf_Success() {
        byte[] pdfBytes = "PDF-BYTES".getBytes();
        when(applicationService.exportReportPdf("rep-1")).thenReturn(new com.ai.guide.application.analytics.ReportExportResult(pdfBytes, "2026-10-04"));

        ResponseEntity<byte[]> response = controller.exportReportPdf("rep-1");
        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(pdfBytes, response.getBody());
        assertTrue(response.getHeaders().getContentDisposition().toString().contains("report_2026-10-04.pdf"));
    }

    @Test
    void testExportReportPdf_NullBytes() {
        when(applicationService.exportReportPdf("rep-1")).thenReturn(new com.ai.guide.application.analytics.ReportExportResult(null, "2026-10-04"));

        ResponseEntity<byte[]> response = controller.exportReportPdf("rep-1");
        assertEquals(500, response.getStatusCode().value());
        assertEquals("PDF 生成失败", new String(response.getBody()));
    }

    @Test
    void testExportReportPdf_Exception() {
        when(applicationService.exportReportPdf(anyString())).thenThrow(new RuntimeException("pdf error"));
        ResponseEntity<byte[]> response = controller.exportReportPdf("rep-err");
        assertEquals(500, response.getStatusCode().value());
        assertEquals("导出失败，请稍后重试", new String(response.getBody()));
    }

    @Test
    void testExportToday_Success() {
        byte[] pdfBytes = "TODAY-PDF".getBytes();
        when(applicationService.exportTodayReportPdf()).thenReturn(pdfBytes);

        ResponseEntity<byte[]> response = controller.exportToday();
        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(pdfBytes, response.getBody());
        assertTrue(response.getHeaders().getContentDisposition().toString().contains("daily_report_"));
    }

    @Test
    void testExportToday_NullBytes() {
        when(applicationService.exportTodayReportPdf()).thenReturn(null);
        ResponseEntity<byte[]> response = controller.exportToday();
        assertEquals(500, response.getStatusCode().value());
        assertEquals("PDF 生成失败", new String(response.getBody()));
    }

    @Test
    void testExportToday_Exception() {
        when(applicationService.exportTodayReportPdf()).thenThrow(new RuntimeException("error"));
        ResponseEntity<byte[]> response = controller.exportToday();
        assertEquals(500, response.getStatusCode().value());
        assertEquals("导出失败，请稍后重试", new String(response.getBody()));
    }
}
