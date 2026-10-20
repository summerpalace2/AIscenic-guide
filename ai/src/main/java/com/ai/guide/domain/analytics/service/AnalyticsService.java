package com.ai.guide.domain.analytics.service;

import com.ai.guide.domain.analytics.gateway.AnalyticsRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Operational analytics rules and report orchestration. */
@Service
public class AnalyticsService {
    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final AnalyticsRepositoryPort repository;
    private final SentimentService sentimentService;
    private final EmotionAnalysisService emotionAnalysisService;

    public AnalyticsService(AnalyticsRepositoryPort repository, SentimentService sentimentService,
                            EmotionAnalysisService emotionAnalysisService) {
        this.repository = repository;
        this.sentimentService = sentimentService;
        this.emotionAnalysisService = emotionAnalysisService;
    }

    public String logService(String sessionId, String question, String emotion, String intent, long responseTimeMs) {
        String id = UUID.randomUUID().toString();
        try {
            repository.insertServiceLog(id, value(sessionId), value(question), value(emotion), value(intent),
                    responseTimeMs, LocalDateTime.now().format(DT_FMT));
            emotionAnalysisService.classifyAsync(question).thenAccept(classified -> updateEmotion(id, classified));
        } catch (Exception e) {
            log.warn("[Analytics] 日志记录失败: {}", e.getMessage());
        }
        return id;
    }

    private void updateEmotion(String id, SentimentService.Sentiment sentiment) {
        if (sentiment == null) return;
        try { repository.updateEmotion(id, sentiment.name()); }
        catch (Exception e) { log.debug("[Analytics] 异步情绪写回失败: {}", e.getMessage()); }
    }

    public Map<String, Object> dashboard(String period) {
        LocalDateTime selectedStart = periodStart(period);
        String selectedSince = selectedStart.format(DT_FMT);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", period);
        refreshDerivedEmotions();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime weekAgo = todayStart.minusDays(6);
        int todayCount = repository.countLogsFrom(todayStart.format(DT_FMT));
        int weekCount = repository.countLogsFrom(weekAgo.format(DT_FMT));
        int prevCount = repository.countLogsBetween(yesterdayStart.format(DT_FMT), todayStart.format(DT_FMT));
        String trend = "0%";
        if (prevCount > 0) {
            double change = ((double) (todayCount - prevCount) / prevCount) * 100;
            trend = (change >= 0 ? "+" : "") + String.format("%.1f%%", change);
        }
        Map<String, Object> serviceCount = new LinkedHashMap<>();
        serviceCount.put("total", repository.countAllLogs());
        serviceCount.put("selected", repository.countLogsFrom(selectedSince));
        serviceCount.put("today", todayCount);
        serviceCount.put("week", weekCount);
        serviceCount.put("trend", trend);
        result.put("serviceCount", serviceCount);
        int activeUsers = repository.countSessionsFrom(selectedSince);
        result.put("activeUsers", Map.of("current", activeUsers, "peakToday", activeUsers));
        result.put("hotQuestionsTop10", repository.hotQuestions(selectedSince, 10));

        int positive = repository.countEmotionFrom("POSITIVE", selectedSince);
        int neutral = repository.countEmotionFrom("NEUTRAL", selectedSince);
        int negative = repository.countEmotionFrom("NEGATIVE", selectedSince);
        int totalEmotion = Math.max(1, positive + neutral + negative);
        result.put("emotionDistribution", Map.of(
                "positive", Math.round((double) positive / totalEmotion * 100.0) / 100.0,
                "neutral", Math.round((double) neutral / totalEmotion * 100.0) / 100.0,
                "negative", Math.round((double) negative / totalEmotion * 100.0) / 100.0));
        result.put("satisfactionTrend", repository.satisfactionTrend(selectedSince));
        return result;
    }

    private void refreshDerivedEmotions() {
        try {
            for (AnalyticsRepositoryPort.EmotionQuestion row : repository.findUnclassifiedQuestions()) {
                SentimentService.Sentiment detected = sentimentService.analyze(Objects.toString(row.question(), ""));
                if (detected != SentimentService.Sentiment.NEUTRAL) repository.updateEmotion(row.id(), detected.name());
            }
        } catch (Exception e) { log.debug("[Analytics] 历史情感回填跳过: {}", e.getMessage()); }
    }

    public List<Map<String, Object>> hotQuestions(String period, int topN) {
        if (topN < 1 || topN > 100) throw new IllegalArgumentException("topN须为1至100");
        return repository.hotQuestions(periodStart(period).format(DT_FMT), topN);
    }

    public List<Map<String, Object>> sentimentTrend(String startDate, String endDate, String granularity) {
        String[] range = dateRange(startDate, endDate);
        Map<String, Map<String, Integer>> aggregated = new LinkedHashMap<>();
        for (AnalyticsRepositoryPort.EmotionBucket row : repository.sentimentBuckets(range[0], range[1], granularity)) {
            String period = bucketLabel(row.period(), granularity);
            aggregated.computeIfAbsent(period, ignored -> new HashMap<>()).merge(row.emotion(), row.count(), Integer::sum);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : aggregated.entrySet()) {
            Map<String, Integer> emotions = entry.getValue();
            int total = Math.max(1, emotions.values().stream().mapToInt(Integer::intValue).sum());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("time", entry.getKey());
            row.put("positive", Math.round((double) emotions.getOrDefault("POSITIVE", 0) / total * 100.0) / 100.0);
            row.put("neutral", Math.round((double) emotions.getOrDefault("NEUTRAL", 0) / total * 100.0) / 100.0);
            row.put("negative", Math.round((double) emotions.getOrDefault("NEGATIVE", 0) / total * 100.0) / 100.0);
            result.add(row);
        }
        return result;
    }

    public List<Map<String, Object>> serviceCount(String startDate, String endDate, String granularity) {
        String[] range = dateRange(startDate, endDate);
        Map<String, Integer> grouped = new LinkedHashMap<>();
        for (AnalyticsRepositoryPort.CountBucket row : repository.serviceBuckets(range[0], range[1], granularity)) {
            grouped.merge(bucketLabel(row.period(), granularity), row.count(), Integer::sum);
        }
        return grouped.entrySet().stream().map(entry -> Map.<String, Object>of("period", entry.getKey(), "count", entry.getValue())).toList();
    }

    public Map<String, Object> listReports(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页须为page>=1且size为1至100");
        return Map.of("total", repository.countReports(), "items", repository.listReports(size, (page - 1) * size));
    }

    public Map<String, Object> getReport(String reportId) { return repository.findReport(reportId); }

    @Scheduled(cron = "0 5 0 * * ?")
    public void dailyInspection() {
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            String dayStart = yesterday.atStartOfDay().format(DT_FMT);
            String dayEnd = yesterday.plusDays(1).atStartOfDay().format(DT_FMT);
            int count = repository.countLogsBetween(dayStart, dayEnd);
            Map<String, Object> data = Map.of("date", yesterday.format(DATE_FMT), "totalServices", count);
            repository.insertDailyReport(UUID.randomUUID().toString(), yesterday.format(DATE_FMT) + " 日服务报告",
                    dayStart, dayEnd, mapToJson(data), LocalDateTime.now().format(DT_FMT));
            log.info("[Analytics] 日报生成完成: {}", yesterday);
        } catch (Exception e) { log.error("[Analytics] 日报生成失败: {}", e.getMessage()); }
    }

    private static String[] dateRange(String start, String end) {
        LocalDate from = parseDate(start), through = parseDate(end);
        if (from != null && through != null && from.isAfter(through)) throw new IllegalArgumentException("起始日期不能晚于截止日期");
        return new String[]{from == null ? null : from.atStartOfDay().format(DT_FMT),
                through == null ? null : through.plusDays(1).atStartOfDay().format(DT_FMT)};
    }
    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return LocalDate.parse(raw); }
        catch (java.time.DateTimeException e) { throw new IllegalArgumentException("日期须为yyyy-MM-dd", e); }
    }
    private static LocalDateTime periodStart(String period) {
        LocalDate today = LocalDate.now();
        return switch (period == null ? "today" : period) {
            case "today" -> today.atStartOfDay();
            case "week" -> today.minusDays(6).atStartOfDay();
            case "month" -> today.minusDays(29).atStartOfDay();
            case "all" -> LocalDate.of(1, 1, 1).atStartOfDay();
            default -> throw new IllegalArgumentException("统计周期仅支持today/week/month/all");
        };
    }
    private static String bucketLabel(String value, String granularity) {
        if ("week".equals(granularity)) return LocalDate.parse(value).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toString();
        return "hour".equals(granularity) ? value + ":00" : value;
    }
    private static String mapToJson(Map<String, Object> values) {
        return values.entrySet().stream().map(entry -> "\"" + entry.getKey() + "\":" +
                        (entry.getValue() instanceof Number ? entry.getValue() : "\"" + entry.getValue() + "\""))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
    }
    private static String value(String input) { return input == null ? "" : input; }
}
