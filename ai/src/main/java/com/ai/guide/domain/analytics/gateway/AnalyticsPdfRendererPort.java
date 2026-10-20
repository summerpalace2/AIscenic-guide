package com.ai.guide.domain.analytics.gateway;

import java.util.Map;

/** PDF document rendering capability implemented by the infrastructure adapter. */
public interface AnalyticsPdfRendererPort {
    byte[] exportDailyReport(String date);
    byte[] exportStoredReport(Map<String, Object> report);
}
