package com.ai.guide.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 数据分析 Service
 * 对应 Python 版本: backend/app/api/v1/analytics.py + backend/app/models/analytics.py
 *
 * 原始 Python 由 sleepearlyplease 创建，Java 转化由 summerpalace2 实现
 *
 * 功能：
 * - 对话完成后自动记录 service_log（情感、意图、耗时）
 * - Dashboard 聚合统计（服务量/活跃用户/热门问题/情绪分布/趋势）
 * - 定时每日巡检生成日报
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsService(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ───────── 1. 日志记录（由 ChatController 调用）─────────

    /**
     * 记录一次 AI 服务调用
     * 由 summerpalace2 添加到 ChatController 的流完成后回调
     */
    public void logService(String sessionId, String question, String emotion, String intent, long responseTimeMs) {
        try {
            String id = UUID.randomUUID().toString();
            String now = LocalDateTime.now().format(DT_FMT);
            jdbcTemplate.update(
                "INSERT INTO service_log (id, session_id, question, emotion, intent, response_time_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, sessionId != null ? sessionId : "", question != null ? question : "",
                emotion != null ? emotion : "", intent != null ? intent : "",
                responseTimeMs, now
            );
        } catch (Exception e) {
            log.warn("[Analytics] 日志记录失败: {}", e.getMessage());
        }
    }

    // ───────── 2. Dashboard 聚合 ─────────

    /**
     * Dashboard 综合统计
     * 对应 Python: GET /analytics/dashboard?period=today
     */
    public Map<String, Object> dashboard(String period) {
        Map<String, Object> result = new LinkedHashMap<>();

        // 时间范围
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        LocalDateTime yesterdayStart = todayStart.minusDays(1);
        LocalDateTime weekAgo = todayStart.minusDays(7);

        // 服务次数
        int total = queryCount("SELECT COUNT(*) FROM service_log");
        int todayCount = queryCount("SELECT COUNT(*) FROM service_log WHERE created_at >= ?", todayStart.format(DT_FMT));
        int weekCount = queryCount("SELECT COUNT(*) FROM service_log WHERE created_at >= ?", weekAgo.format(DT_FMT));

        // 趋势（环比昨日）
        int prevCount = queryCount("SELECT COUNT(*) FROM service_log WHERE created_at >= ? AND created_at < ?",
                yesterdayStart.format(DT_FMT), todayStart.format(DT_FMT));
        String trend = "0%";
        if (prevCount > 0) {
            double change = ((double)(todayCount - prevCount) / prevCount) * 100;
            trend = (change >= 0 ? "+" : "") + String.format("%.1f%%", change);
        }

        Map<String, Object> serviceCount = new LinkedHashMap<>();
        serviceCount.put("total", total);
        serviceCount.put("today", todayCount);
        serviceCount.put("week", weekCount);
        serviceCount.put("trend", trend);
        result.put("serviceCount", serviceCount);

        // 活跃用户（今日独立 session 数）
        int activeUsers = queryCount("SELECT COUNT(DISTINCT session_id) FROM service_log WHERE created_at >= ?", todayStart.format(DT_FMT));
        Map<String, Object> activeUsersData = new LinkedHashMap<>();
        activeUsersData.put("current", activeUsers);
        activeUsersData.put("peakToday", activeUsers);
        result.put("activeUsers", activeUsersData);

        // 热门问题 TOP10
        result.put("hotQuestionsTop10", queryHotQuestions(todayStart.format(DT_FMT), 10));

        // 情绪分布
        int pos = queryCount("SELECT COUNT(*) FROM service_log WHERE emotion = 'POSITIVE' AND created_at >= ?", todayStart.format(DT_FMT));
        int neu = queryCount("SELECT COUNT(*) FROM service_log WHERE emotion = 'NEUTRAL' AND created_at >= ?", todayStart.format(DT_FMT));
        int neg = queryCount("SELECT COUNT(*) FROM service_log WHERE emotion = 'NEGATIVE' AND created_at >= ?", todayStart.format(DT_FMT));
        int totalEmo = pos + neu + neg;
        if (totalEmo == 0) totalEmo = 1;

        Map<String, Object> emotionDist = new LinkedHashMap<>();
        emotionDist.put("positive", Math.round((double) pos / totalEmo * 100.0) / 100.0);
        emotionDist.put("neutral", Math.round((double) neu / totalEmo * 100.0) / 100.0);
        emotionDist.put("negative", Math.round((double) neg / totalEmo * 100.0) / 100.0);
        result.put("emotionDistribution", emotionDist);

        // 满意度趋势（近7天积极率）
        result.put("satisfactionTrend", querySatisfactionTrend(weekAgo.format(DT_FMT)));

        return result;
    }

    // ───────── 3. 热门问题排行 ─────────

    public List<Map<String, Object>> hotQuestions(String period, int topN) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        return queryHotQuestions(todayStart.format(DT_FMT), topN);
    }

    // ───────── 4. 情绪趋势 ─────────

    public List<Map<String, Object>> sentimentTrend(String startDate, String endDate, String granularity) {
        String groupBy = "date(created_at)";
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(groupBy).append(" as period, emotion, COUNT(*) as cnt ");
        sql.append("FROM service_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND created_at >= ?");
            params.add(startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND created_at <= ?");
            params.add(endDate);
        }
        sql.append(" GROUP BY ").append(groupBy).append(", emotion ORDER BY ").append(groupBy);

        List<Map<String, Object>> rows = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", rs.getString("period"));
            m.put("emotion", rs.getString("emotion"));
            m.put("count", rs.getInt("cnt"));
            return m;
        }, params.toArray());

        // 聚合为 {time, positive, neutral, negative}
        Map<String, Map<String, Integer>> aggregated = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String period = (String) row.get("period");
            String emotion = (String) row.get("emotion");
            int count = (Integer) row.get("count");
            aggregated.computeIfAbsent(period, k -> new HashMap<>());
            aggregated.get(period).put(emotion, count);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : aggregated.entrySet()) {
            Map<String, Integer> emotions = entry.getValue();
            int total = emotions.values().stream().mapToInt(Integer::intValue).sum();
            if (total == 0) total = 1;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("time", entry.getKey());
            m.put("positive", Math.round((double) emotions.getOrDefault("POSITIVE", 0) / total * 100.0) / 100.0);
            m.put("neutral", Math.round((double) emotions.getOrDefault("NEUTRAL", 0) / total * 100.0) / 100.0);
            m.put("negative", Math.round((double) emotions.getOrDefault("NEGATIVE", 0) / total * 100.0) / 100.0);
            result.add(m);
        }
        return result;
    }

    // ───────── 5. 服务量时序 ─────────

    public List<Map<String, Object>> serviceCount(String startDate, String endDate, String granularity) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT date(created_at) as period, COUNT(*) as cnt FROM service_log WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (startDate != null) { sql.append(" AND created_at >= ?"); params.add(startDate); }
        if (endDate != null) { sql.append(" AND created_at <= ?"); params.add(endDate); }
        sql.append(" GROUP BY date(created_at) ORDER BY date(created_at)");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", rs.getString("period"));
            m.put("count", rs.getInt("cnt"));
            return m;
        }, params.toArray());
    }

    // ───────── 6. 报告管理 ─────────

    public Map<String, Object> listReports(int page, int size) {
        int total = queryCount("SELECT COUNT(*) FROM analytics_report");
        List<Map<String, Object>> items = jdbcTemplate.query(
            "SELECT id, title, type, period_start, period_end, status, created_at FROM analytics_report ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("title", rs.getString("title"));
                m.put("type", rs.getString("type"));
                m.put("periodStart", rs.getString("period_start"));
                m.put("periodEnd", rs.getString("period_end"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }, size, (page - 1) * size
        );
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("items", items);
        return result;
    }

    public Map<String, Object> getReport(String reportId) {
        List<Map<String, Object>> items = jdbcTemplate.query(
            "SELECT * FROM analytics_report WHERE id = ?",
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("title", rs.getString("title"));
                m.put("type", rs.getString("type"));
                m.put("periodStart", rs.getString("period_start"));
                m.put("periodEnd", rs.getString("period_end"));
                m.put("status", rs.getString("status"));
                m.put("data", rs.getString("data"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }, reportId
        );
        return items.isEmpty() ? null : items.get(0);
    }

    // ───────── 7. 定时每日巡检（对应 Python scheduler.py）─────────

    /**
     * 每日 00:05 生成前一天日报
     * 由 summerpalace2 实现，对应 Python: daily_inspection()
     */
    @Scheduled(cron = "0 5 0 * * ?")
    public void dailyInspection() {
        try {
            LocalDate yesterday = LocalDate.now().minusDays(1);
            String dayStart = yesterday.atStartOfDay().format(DT_FMT);
            String dayEnd = yesterday.plusDays(1).atStartOfDay().format(DT_FMT);

            int msgCount = queryCount("SELECT COUNT(*) FROM service_log WHERE created_at >= ? AND created_at < ?", dayStart, dayEnd);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("date", yesterday.format(DATE_FMT));
            data.put("totalServices", msgCount);

            String id = UUID.randomUUID().toString();
            String now = LocalDateTime.now().format(DT_FMT);
            jdbcTemplate.update(
                "INSERT INTO analytics_report (id, title, type, period_start, period_end, status, data, created_at) VALUES (?, ?, 'daily', ?, ?, 'completed', ?, ?)",
                id, yesterday.format(DATE_FMT) + " 日服务报告", dayStart, dayEnd,
                mapToJson(data), now
            );
            log.info("[Analytics] 日报生成完成: {}", yesterday);
        } catch (Exception e) {
            log.error("[Analytics] 日报生成失败: {}", e.getMessage());
        }
    }

    // ───────── 内部查询方法 ─────────

    private int queryCount(String sql, Object... args) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return count != null ? count : 0;
    }

    private List<Map<String, Object>> queryHotQuestions(String since, int limit) {
        return jdbcTemplate.query(
            "SELECT question, COUNT(*) as cnt FROM service_log WHERE created_at >= ? AND question != '' GROUP BY question ORDER BY cnt DESC LIMIT ?",
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("rank", rowNum + 1);
                m.put("question", rs.getString("question"));
                m.put("count", rs.getInt("cnt"));
                return m;
            }, since, limit
        );
    }

    private List<Map<String, Object>> querySatisfactionTrend(String since) {
        List<Map<String, Object>> rows = jdbcTemplate.query(
            "SELECT date(created_at) as period, " +
            "SUM(CASE WHEN emotion = 'POSITIVE' THEN 1 ELSE 0 END) as pos, " +
            "COUNT(*) as total " +
            "FROM service_log WHERE created_at >= ? GROUP BY date(created_at) ORDER BY date(created_at)",
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", rs.getString("period"));
                int total = rs.getInt("total");
                int pos = rs.getInt("pos");
                m.put("score", total > 0 ? Math.round((double) pos / total * 100.0) / 100.0 : 0);
                return m;
            }, since
        );
        return rows;
    }

    private String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            sb.append("\"").append(e.getKey()).append("\":");
            if (e.getValue() instanceof Number) sb.append(e.getValue());
            else sb.append("\"").append(e.getValue()).append("\"");
            first = false;
        }
        sb.append("}");
        return sb.toString();
    }
}
