package com.ai.guide.infrastructure.analytics.repository;

import com.ai.guide.domain.analytics.gateway.AnalyticsRepositoryPort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JDBC persistence adapter for operational analytics. */
@Repository
public class JdbcAnalyticsRepositoryAdapter implements AnalyticsRepositoryPort {
    private final JdbcTemplate jdbc;

    public JdbcAnalyticsRepositoryAdapter(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public void insertServiceLog(String id, String sessionId, String question, String emotion, String intent,
                                           long responseTimeMs, String createdAt) {
        jdbc.update("INSERT INTO service_log (id, session_id, question, emotion, intent, response_time_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, sessionId, question, emotion, intent, responseTimeMs, createdAt);
    }
    @Override public void updateEmotion(String id, String emotion) {
        jdbc.update("UPDATE service_log SET emotion = ? WHERE id = ?", emotion, id);
    }
    @Override public List<EmotionQuestion> findUnclassifiedQuestions() {
        return jdbc.query("SELECT id, question FROM service_log WHERE question IS NOT NULL AND question <> '' AND (emotion IS NULL OR emotion = '' OR emotion = 'NEUTRAL')",
                (rs, row) -> new EmotionQuestion(rs.getString("id"), rs.getString("question")));
    }
    @Override public int countAllLogs() { return count("SELECT COUNT(*) FROM service_log"); }
    @Override public int countLogsFrom(String since) { return count("SELECT COUNT(*) FROM service_log WHERE created_at >= ?", since); }
    @Override public int countLogsBetween(String startInclusive, String endExclusive) {
        return count("SELECT COUNT(*) FROM service_log WHERE created_at >= ? AND created_at < ?", startInclusive, endExclusive);
    }
    @Override public int countSessionsFrom(String since) {
        return count("SELECT COUNT(DISTINCT session_id) FROM service_log WHERE created_at >= ?", since);
    }
    @Override public int countEmotionFrom(String emotion, String since) {
        return count("SELECT COUNT(*) FROM service_log WHERE emotion = ? AND created_at >= ?", emotion, since);
    }
    @Override public List<EmotionBucket> sentimentBuckets(String start, String end, String granularity) {
        String bucket = bucketSql(granularity);
        SqlRange range = range(start, end);
        String sql = "SELECT " + bucket + " AS period, emotion, COUNT(*) AS cnt FROM service_log WHERE 1=1" + range.sql()
                + " GROUP BY " + bucket + ", emotion ORDER BY " + bucket;
        return jdbc.query(sql, (rs, row) -> new EmotionBucket(rs.getString("period"), rs.getString("emotion"), rs.getInt("cnt")), range.args());
    }
    @Override public List<CountBucket> serviceBuckets(String start, String end, String granularity) {
        String bucket = bucketSql(granularity);
        SqlRange range = range(start, end);
        String sql = "SELECT " + bucket + " AS period, COUNT(*) AS cnt FROM service_log WHERE 1=1" + range.sql()
                + " GROUP BY " + bucket + " ORDER BY " + bucket;
        return jdbc.query(sql, (rs, row) -> new CountBucket(rs.getString("period"), rs.getInt("cnt")), range.args());
    }
    @Override public List<Map<String, Object>> hotQuestions(String since, int limit) {
        return jdbc.query("SELECT question, COUNT(*) AS cnt FROM service_log WHERE created_at >= ? AND question != '' GROUP BY question ORDER BY cnt DESC LIMIT ?",
                (rs, row) -> Map.<String, Object>of("rank", row + 1, "question", rs.getString("question"), "count", rs.getInt("cnt")), since, limit);
    }
    @Override public List<Map<String, Object>> satisfactionTrend(String since) {
        return jdbc.query("SELECT substr(created_at,1,10) AS period, SUM(CASE WHEN emotion = 'POSITIVE' THEN 1 ELSE 0 END) AS pos, COUNT(*) AS total FROM service_log WHERE created_at >= ? GROUP BY substr(created_at,1,10) ORDER BY substr(created_at,1,10)",
                (rs, row) -> {
                    int total = rs.getInt("total");
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("date", rs.getString("period"));
                    result.put("score", total > 0 ? Math.round((double) rs.getInt("pos") / total * 100.0) / 100.0 : 0);
                    return result;
                }, since);
    }
    @Override public int countReports() { return count("SELECT COUNT(*) FROM analytics_report"); }
    @Override public List<Map<String, Object>> listReports(int limit, int offset) {
        return jdbc.query("SELECT id, title, type, period_start, period_end, status, created_at FROM analytics_report ORDER BY created_at DESC LIMIT ? OFFSET ?",
                (rs, row) -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("id", rs.getString("id")); result.put("title", rs.getString("title"));
                    result.put("type", rs.getString("type")); result.put("periodStart", rs.getString("period_start"));
                    result.put("periodEnd", rs.getString("period_end")); result.put("status", rs.getString("status"));
                    result.put("createdAt", rs.getString("created_at")); return result;
                }, limit, offset);
    }
    @Override public Map<String, Object> findReport(String id) {
        List<Map<String, Object>> items = jdbc.query("SELECT * FROM analytics_report WHERE id = ?", (rs, row) -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", rs.getString("id")); result.put("title", rs.getString("title")); result.put("type", rs.getString("type"));
            result.put("periodStart", rs.getString("period_start")); result.put("periodEnd", rs.getString("period_end"));
            result.put("status", rs.getString("status")); result.put("data", rs.getString("data"));
            result.put("createdAt", rs.getString("created_at")); return result;
        }, id);
        return items.isEmpty() ? null : items.get(0);
    }
    @Override public void insertDailyReport(String id, String title, String start, String end, String data, String createdAt) {
        jdbc.update("INSERT INTO analytics_report (id, title, type, period_start, period_end, status, data, created_at) VALUES (?, ?, 'daily', ?, ?, 'completed', ?, ?)",
                id, title, start, end, data, createdAt);
    }
    @Override public DailyReportData loadDailyReportData(String start, String end) {
        int total = count("SELECT COUNT(*) FROM service_log WHERE created_at >= ? AND created_at < ?", start, end);
        int positive = count("SELECT COUNT(*) FROM service_log WHERE emotion = 'POSITIVE' AND created_at >= ? AND created_at < ?", start, end);
        int negative = count("SELECT COUNT(*) FROM service_log WHERE emotion = 'NEGATIVE' AND created_at >= ? AND created_at < ?", start, end);
        List<Map<String, Object>> hot = jdbc.query("SELECT question, COUNT(*) AS cnt FROM service_log WHERE created_at >= ? AND created_at < ? AND question != '' GROUP BY question ORDER BY cnt DESC LIMIT 5",
                (rs, row) -> Map.<String, Object>of("rank", row + 1, "question", rs.getString("question"), "count", rs.getInt("cnt")), start, end);
        return new DailyReportData(total, positive, negative, hot);
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
    private static String bucketSql(String granularity) {
        return switch (granularity == null ? "day" : granularity) {
            case "hour" -> "substr(created_at,1,13)";
            case "day", "week" -> "substr(created_at,1,10)";
            case "month" -> "substr(created_at,1,7)";
            default -> throw new IllegalArgumentException("Unsupported analytics granularity");
        };
    }
    private static SqlRange range(String start, String end) {
        StringBuilder sql = new StringBuilder();
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        if (start != null) { sql.append(" AND created_at >= ?"); args.add(start); }
        if (end != null) { sql.append(" AND created_at < ?"); args.add(end); }
        return new SqlRange(sql.toString(), args.toArray());
    }
    private record SqlRange(String sql, Object[] args) { }
}
