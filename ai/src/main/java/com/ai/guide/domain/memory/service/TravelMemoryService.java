package com.ai.guide.domain.memory.service;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.preferences.api.UserPreferencesPatch;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.service.PreferencesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Lightweight, user-confirmed travel memory store. It intentionally does not use a vector database. */
@Service
public class TravelMemoryService {
    private final JdbcTemplate jdbc;
    private final PreferencesService preferencesService;

    /** Lightweight constructor retained for pure candidate unit tests. */
    public TravelMemoryService(JdbcTemplate jdbc) {
        this(jdbc, null);
    }

    @Autowired
    public TravelMemoryService(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc,
                               PreferencesService preferencesService) {
        this.jdbc = jdbc;
        this.preferencesService = preferencesService;
    }

    public List<TravelMemory> list(String userId) {
        if (!validUser(userId)) return List.of();
        return jdbc.query("SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory WHERE user_id = ? AND status = 'CONFIRMED' ORDER BY updated_at DESC LIMIT 30",
                (rs, row) -> new TravelMemory(rs.getString("id"), rs.getString("category"), rs.getString("content"),
                        rs.getString("source_type"), rs.getString("source_ref"), rs.getString("status"),
                        rs.getDouble("confidence"), rs.getLong("created_at"), rs.getLong("updated_at")), userId);
    }

    public Candidate suggest(String message) {
        String text = clean(message, 400);
        if (text.isBlank() || isCurrentTripOnly(text) || !hasStablePersonalSignal(text)) return null;
        if (text.matches(".*(轮椅|无障碍|婴儿车|腿脚不便|行动不便).*")) return new Candidate("ACCESSIBILITY", "出行需要无障碍、少台阶与便于休息的安排", 0.95);
        if (text.matches(".*(少走路|不想走太多|少爬坡|轻松一点|不爱走路|体力.*有限).*")) return new Candidate("PACE", "偏好少走路，优先平路、电梯与短步行", 0.93);
        if (text.matches(".*(过敏|忌口|清真|素食|纯素).*")) return new Candidate("DIET", "饮食有明确限制，需要优先确认可选餐食", 0.96);
        if (text.matches(".*(不吃辣|少辣|清淡|不吃香菜|不吃.*海鲜).*")) return new Candidate("DIET", "饮食偏好清淡，尽量少辣并避开明确忌口", 0.92);
        if (text.matches(".*(打车|出租车|网约车).*")) return new Candidate("TRANSPORT", "出行时优先打车或网约车", 0.91);
        if (text.matches(".*(地铁|公交|公共交通).*")) return new Candidate("TRANSPORT", "出行时更愿意使用公共交通", 0.86);
        if (text.matches(".*(预算|省钱|性价比|免费).*")) return new Candidate("BUDGET", "出行时重视预算与性价比", 0.85);
        if (text.matches(".*(怕晒|怕热|雨天|下雨.*室内|避暑).*")) return new Candidate("WEATHER", "行程更在意天气体感，优先阴凉、室内或可避雨安排", 0.87);
        if (text.matches(".*(不喜欢早起|睡到自然醒|晚起).*")) return new Candidate("TIME", "出行节奏不宜太早，偏好从容安排", 0.84);
        if (text.matches(".*(酒店.*安静|住宿.*安静|住得.*方便).*")) return new Candidate("STAY", "住宿更看重安静与交通便利", 0.82);
        if (text.matches(".*(情侣|对象|爱人|两个人).*")) return new Candidate("COMPANION", "通常以情侣出游方式安排行程", 0.84);
        if (text.matches(".*(带娃|孩子|亲子).*")) return new Candidate("COMPANION", "出行时常需要兼顾亲子体验", 0.84);
        if (text.matches(".*(父母|长辈|老人).*")) return new Candidate("COMPANION", "出行时常需要兼顾长辈的舒适度", 0.84);
        if (text.matches(".*(博物馆|美术馆|展览|人文|历史|古迹).*")) return new Candidate("INTEREST", "偏爱人文历史与室内文化场馆", 0.87);
        if (text.matches(".*(夜景|江景).*")) return new Candidate("INTEREST", "喜欢山城夜景与江景体验", 0.84);
        if (text.matches(".*(拍照|摄影|出片).*")) return new Candidate("INTEREST", "旅行时重视拍照与景观视角", 0.83);
        if (text.matches(".*(自然|徒步|爬山|山水).*")) return new Candidate("INTEREST", "偏爱自然山水与户外景观", 0.81);
        return null;
    }

    public TravelMemory confirm(String userId, String content, String category, String sourceType) {
        requireUser(userId);
        String safeContent = clean(content, 240);
        if (safeContent.isBlank()) throw new IllegalArgumentException("记忆内容不能为空");
        String safeCategory = normalizeCategory(category);
        long now = System.currentTimeMillis();
        List<TravelMemory> same = jdbc.query("SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory WHERE user_id = ? AND category = ? AND content = ? AND status = 'CONFIRMED' LIMIT 1",
                (rs, row) -> new TravelMemory(rs.getString("id"), rs.getString("category"), rs.getString("content"),
                        rs.getString("source_type"), rs.getString("source_ref"), rs.getString("status"), rs.getDouble("confidence"),
                        rs.getLong("created_at"), rs.getLong("updated_at")), userId, safeCategory, safeContent);
        if (!same.isEmpty()) {
            TravelMemory existing = same.get(0);
            jdbc.update("UPDATE travel_memory SET updated_at = ? WHERE id = ? AND user_id = ?", now, existing.id(), userId);
            return new TravelMemory(existing.id(), existing.category(), existing.content(), existing.sourceType(), existing.sourceRef(), existing.status(), existing.confidence(), existing.createdAt(), now);
        }
        String id = "mem-" + UUID.randomUUID();
        String source = "USER_EDIT".equalsIgnoreCase(sourceType) ? "USER_EDIT" : "CHAT_CONFIRMED";
        jdbc.update("INSERT INTO travel_memory (id, user_id, category, content, source_type, source_ref, status, confidence, created_at, updated_at) VALUES (?, ?, ?, ?, ?, '', 'CONFIRMED', ?, ?, ?)",
                id, userId, safeCategory, safeContent, source, source.equals("USER_EDIT") ? 1.0 : 0.9, now, now);
        return new TravelMemory(id, safeCategory, safeContent, source, "", "CONFIRMED", source.equals("USER_EDIT") ? 1.0 : 0.9, now, now);
    }

    public TravelMemory update(String userId, String id, String content, String category) {
        requireUser(userId);
        String safeContent = clean(content, 240);
        if (safeContent.isBlank()) throw new IllegalArgumentException("记忆内容不能为空");
        int changed = jdbc.update("UPDATE travel_memory SET content = ?, category = ?, source_type = 'USER_EDIT', confidence = 1.0, updated_at = ? WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'",
                safeContent, normalizeCategory(category), System.currentTimeMillis(), id, userId);
        if (changed == 0) throw new IllegalArgumentException("旅行记忆不存在或无权修改");
        return list(userId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    public void delete(String userId, String id) {
        requireUser(userId);
        jdbc.update("UPDATE travel_memory SET status = 'DELETED', updated_at = ? WHERE id = ? AND user_id = ?", System.currentTimeMillis(), id, userId);
    }

    /** Stores one concise user preference cue, never a full transcript or assistant reply. */
    public void captureObservation(String userId, String content, String sourceSession) {
        requireUser(userId);
        if (jdbc == null) return;
        String cue = clean(content, 400);
        if (cue.isBlank() || suggest(cue) == null) return;
        long now = System.currentTimeMillis();
        String session = clean(sourceSession, 120);
        Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM travel_memory_observation WHERE user_id = ? AND content = ? AND status = 'PENDING'", Integer.class, userId, cue);
        if (duplicate != null && duplicate > 0) return;
        jdbc.update("INSERT INTO travel_memory_observation (id, user_id, content, source_session, status, created_at) VALUES (?, ?, ?, ?, 'PENDING', ?)",
                "obs-" + UUID.randomUUID(), userId, cue, session, now);
    }

    public List<MemoryCandidate> candidates(String userId) {
        requireUser(userId);
        if (jdbc == null) return List.of();
        return jdbc.query("SELECT id, category, content, source_ref, status, confidence, created_at, updated_at FROM travel_memory_candidate WHERE user_id = ? AND status = 'PENDING' ORDER BY updated_at DESC LIMIT 12",
                (rs, row) -> new MemoryCandidate(rs.getString("id"), rs.getString("category"), rs.getString("content"), rs.getString("source_ref"), rs.getString("status"), rs.getDouble("confidence"), rs.getLong("created_at"), rs.getLong("updated_at")), userId);
    }

    public TravelMemory confirmCandidate(String userId, String candidateId) {
        requireUser(userId);
        if (jdbc == null) throw new IllegalArgumentException("记忆服务暂不可用");
        List<MemoryCandidate> rows = jdbc.query("SELECT id, category, content, source_ref, status, confidence, created_at, updated_at FROM travel_memory_candidate WHERE id = ? AND user_id = ? AND status = 'PENDING'",
                (rs, row) -> new MemoryCandidate(rs.getString("id"), rs.getString("category"), rs.getString("content"), rs.getString("source_ref"), rs.getString("status"), rs.getDouble("confidence"), rs.getLong("created_at"), rs.getLong("updated_at")), candidateId, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("记忆候选不存在或已处理");
        MemoryCandidate candidate = rows.get(0);
        TravelMemory memory = confirm(userId, candidate.content(), candidate.category(), "CHAT_CONFIRMED");
        jdbc.update("UPDATE travel_memory_candidate SET status = 'CONFIRMED', updated_at = ? WHERE id = ? AND user_id = ?", System.currentTimeMillis(), candidateId, userId);
        return memory;
    }

    public void dismissCandidate(String userId, String candidateId) {
        requireUser(userId);
        if (jdbc != null) jdbc.update("UPDATE travel_memory_candidate SET status = 'DISMISSED', updated_at = ? WHERE id = ? AND user_id = ? AND status = 'PENDING'", System.currentTimeMillis(), candidateId, userId);
    }

    /**
     * Genuine background review: it reads only compact user cues that were
     * explicitly selected for memory review. It never imports a memory itself.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void reviewQueuedObservations() {
        if (jdbc == null || preferencesService == null) return;
        List<String> users = jdbc.query("SELECT DISTINCT user_id FROM travel_memory_observation WHERE status = 'PENDING'", (rs, row) -> rs.getString(1));
        for (String userId : users) {
            try { reviewUserObservations(userId); } catch (RuntimeException ignored) { /* retry on the next scheduled pass */ }
        }
    }

    private void reviewUserObservations(String userId) {
        UserPreferences preferences = preferencesService.get(userId);
        Map<String, Object> metadata = new LinkedHashMap<>(preferences.legacyMetadata());
        if (!Boolean.TRUE.equals(metadata.get("travelMemoryEnabled"))) return;
        long now = System.currentTimeMillis();
        long lastReview = number(metadata.get("travelMemoryLastReviewAt"));
        if (lastReview > 0 && now - lastReview < 12 * 60 * 60 * 1000L) return;
        List<Observation> observations = jdbc.query("SELECT id, content FROM travel_memory_observation WHERE user_id = ? AND status = 'PENDING' ORDER BY created_at ASC LIMIT 60",
                (rs, row) -> new Observation(rs.getString("id"), rs.getString("content")), userId);
        for (Observation observation : observations) {
            Candidate detected = suggest(observation.content());
            if (detected == null) continue;
            Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM travel_memory_candidate WHERE user_id = ? AND category = ? AND content = ? AND status = 'PENDING'", Integer.class, userId, detected.category(), detected.content());
            if (duplicate == null || duplicate == 0) {
                String id = "mem-candidate-" + UUID.randomUUID();
                jdbc.update("INSERT INTO travel_memory_candidate (id, user_id, category, content, source_ref, status, confidence, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)",
                        id, userId, detected.category(), detected.content(), observation.id(), detected.confidence(), now, now);
            }
        }
        jdbc.update("UPDATE travel_memory_observation SET status = 'REVIEWED' WHERE user_id = ? AND status = 'PENDING'", userId);
        metadata.put("travelMemoryLastReviewAt", now);
        preferencesService.merge(userId, new UserPreferencesPatch(null, null, null, null, null, null, null, metadata, preferences.revision()), preferences.revision());
    }

    public String promptContext(String userId) {
        List<TravelMemory> items = list(userId);
        if (items.isEmpty()) return "";
        List<String> values = new ArrayList<>();
        for (TravelMemory item : items.stream().limit(6).toList()) values.add(item.content());
        return "【用户已确认的长期旅行记忆】\n" + String.join("；", values)
                + "。仅在不与本次明确需求冲突时参考；不得将其改写或当成当前行程的已确认修改。";
    }

    private boolean isCurrentTripOnly(String text) {
        return text.matches(".*(今天|明天|后天|今晚|这次|本次|当前|这一趟|这趟|当天).*" );
    }

    /**
     * We accept natural personal statements such as “我不吃香菜” without
     * forcing the user to say “记住”. A temporary itinerary instruction still
     * never enters the review queue, and every candidate needs confirmation.
     */
    private boolean hasStablePersonalSignal(String text) {
        return text.matches(".*(记住|以后|默认|通常|习惯|总是|一直|经常|一般|更喜欢|偏爱|不喜欢|不吃|不喝|过敏|怕晒|怕热|受不了|优先|首选|我|我们|家里).*" );
    }

    private String normalizeCategory(String value) {
        String normalized = value == null ? "CUSTOM" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) { case "PACE", "ACCESSIBILITY", "TRANSPORT", "DIET", "COMPANION", "INTEREST", "BUDGET", "WEATHER", "TIME", "STAY", "CUSTOM" -> normalized; default -> "CUSTOM"; };
    }
    private String clean(String value, int max) {
        if (value == null) return "";
        String compact = value.replaceAll("[\\r\\n\\t]+", " ").trim().replaceAll("\\s{2,}", " ");
        return compact.substring(0, Math.min(Math.max(0, max), compact.length()));
    }
    private boolean validUser(String userId) { return userId != null && !userId.isBlank() && !"anonymous".equalsIgnoreCase(userId); }
    private void requireUser(String userId) { if (!validUser(userId)) throw new IllegalArgumentException("请先登录后管理旅行记忆"); }
    private long number(Object value) { try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); } catch (NumberFormatException ignored) { return 0; } }
    public record Candidate(String category, String content, double confidence) { }
    public record MemoryCandidate(String id, String category, String content, String sourceRef, String status, double confidence, long createdAt, long updatedAt) { }
    private record Observation(String id, String content) { }
}
