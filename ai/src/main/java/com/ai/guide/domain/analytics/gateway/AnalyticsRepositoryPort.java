package com.ai.guide.domain.analytics.gateway;

import java.util.List;
import java.util.Map;

/** Persistence capability for operational analytics and saved reports. */
public interface AnalyticsRepositoryPort {
    record EmotionQuestion(String id, String question) {}
    record EmotionBucket(String period, String emotion, int count) {}
    record CountBucket(String period, int count) {}
    record DailyReportData(int total, int positive, int negative, List<Map<String, Object>> hotQuestions) {}

    void insertServiceLog(String id, String sessionId, String question, String emotion, String intent,
                          long responseTimeMs, String createdAt);
    void updateEmotion(String id, String emotion);
    List<EmotionQuestion> findUnclassifiedQuestions();
    int countAllLogs();
    int countLogsFrom(String since);
    int countLogsBetween(String startInclusive, String endExclusive);
    int countSessionsFrom(String since);
    int countEmotionFrom(String emotion, String since);
    List<EmotionBucket> sentimentBuckets(String startInclusive, String endExclusive, String granularity);
    List<CountBucket> serviceBuckets(String startInclusive, String endExclusive, String granularity);
    List<Map<String, Object>> hotQuestions(String since, int limit);
    List<Map<String, Object>> satisfactionTrend(String since);
    int countReports();
    List<Map<String, Object>> listReports(int limit, int offset);
    Map<String, Object> findReport(String id);
    void insertDailyReport(String id, String title, String periodStart, String periodEnd, String data, String createdAt);
    DailyReportData loadDailyReportData(String startInclusive, String endExclusive);
}
