package com.ai.guide.domain.analytics.service;

import com.ai.guide.domain.analytics.gateway.AnalyticsPdfRendererPort;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Application-facing facade; PDF library and font handling live behind the renderer port. */
@Service
public class PdfExportService {
    private final AnalyticsPdfRendererPort renderer;

    public PdfExportService(AnalyticsPdfRendererPort renderer) {
        this.renderer = renderer;
    }

    public byte[] exportDailyReport(String date) { return renderer.exportDailyReport(date); }
    public byte[] exportStoredReport(Map<String, Object> report) { return renderer.exportStoredReport(report); }
}
