package com.ai.guide.domain.planner.narrative;

import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.user.model.User;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.PlannerVersionMetadata;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于事实核验的行程推荐理由生成服务
 *
 * 所属领域：domain.planner.narrative（规划叙事与理由生成）
 * 架构职责：结合景点真实特色、用户偏好契合点与通行路线，为行程各站点生成自然生动且符合事实的推荐理由。
 */
@Service
public class GroundedNarrativeService {

    private static final Logger log = LoggerFactory.getLogger(GroundedNarrativeService.class);
    private static final Pattern JSON_OBJECT_PATTERN = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private static final List<String> STRICT_ASSERTION_KEYWORDS = List.of(
            "全馆", "完全", "一定", "保证", "无需预约", "免预约", "免费", "全程公交"
    );

    private static final String SYSTEM_PROMPT = """
            你是“渝游智策”行程规划助手的推荐理由生成器。
            你的唯一任务是：基于已确定且已排好序的行程事实、景点特征、得分归因、交通核验状态和用户偏好，生成一份整体行程概述（tripSummary）以及针对每个景点的个性化推荐理由（stopReasons）。

            严格限制与安全边界：
            1. 绝对不要新增、删除、替换或重排任何景点。
            2. 只能为输入数据中出现的每一个有效 stopId 生成对应理由，严禁捏造或输出未给出的 stopId。
            3. 推荐理由必须忠实于给出的事实证据（如老人友好、无障碍直梯、平街、夜景机位、室内避雨、亲子科普、交通核验时长等），切勿虚构不存在的设施或活动。
            4. 严禁扩大事实或添加未经证据支持的绝对化断言：
               - 不得使用“全馆、完全、一定、保证、无需预约、免预约、免费、全程公交”等绝对化词汇，除非输入数据中针对该景点明确包含了该事实或政策。
               - 若输入仅显示无障碍支持（SUPPORTED），不得夸大断言为“全馆无障碍”；
               - 若输入未包含免预约或免费，不得自行声称“无需预约”或“免费”；
               - 总体概括不得将单个站点的交通方式夸大为“全程公交优先”。
            5. 必须仅输出合法的 JSON 对象，不要包含 Markdown 围栏代码块（```json）或其他解释文字。

            输出 JSON 格式要求：
            {
              "tripSummary": "整份行程的自然概括说明（50-100字）",
              "stopReasons": {
                "stopId1": "该站点的简明推荐理由（15-40字）",
                "stopId2": "该站点的简明推荐理由（15-40字）"
              }
            }
            """;

    @FunctionalInterface
    public interface NarrativeModelClient {
        String complete(String systemPrompt, String userPrompt) throws Exception;
    }

    private final NarrativeModelClient modelClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final long timeoutMs;
    private final boolean credentialsAvailable;
    private final Executor modelExecutor;

    @Autowired
    public GroundedNarrativeService(ChatClient.Builder builder,
                                   ObjectMapper objectMapper,
                                   @Value("${planner.llm.narrative.enabled:false}") boolean enabled,
                                   @Value("${planner.llm.narrative.timeout-ms:30000}") long timeoutMs,
                                   @Value("${spring.ai.openai.api-key:}") String apiKey,
                                   @Qualifier("plannerLlmExecutor") Executor modelExecutor) {
        ChatClient chatClient = builder.build();
        this.modelClient = (systemPrompt, userPrompt) -> chatClient.prompt()
                .messages(new SystemMessage(systemPrompt), new UserMessage(userPrompt))
                .call()
                .content();
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.timeoutMs = Math.max(500L, timeoutMs);
        this.credentialsAvailable = apiKey != null && !apiKey.isBlank();
        this.modelExecutor = modelExecutor;
    }

    /** Test constructor allowing a fake or mocked model client without network calls. */
    public GroundedNarrativeService(NarrativeModelClient modelClient,
                                   ObjectMapper objectMapper,
                                   boolean enabled,
                                   long timeoutMs,
                                   boolean credentialsAvailable) {
        this(modelClient, objectMapper, enabled, timeoutMs, credentialsAvailable, Runnable::run);
    }

    /** Test constructor allowing an explicit executor boundary. */
    public GroundedNarrativeService(NarrativeModelClient modelClient,
                                   ObjectMapper objectMapper,
                                   boolean enabled,
                                   long timeoutMs,
                                   boolean credentialsAvailable,
                                   Executor modelExecutor) {
        this.modelClient = modelClient;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.timeoutMs = Math.max(1L, timeoutMs);
        this.credentialsAvailable = credentialsAvailable;
        this.modelExecutor = modelExecutor;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isCredentialsAvailable() {
        return credentialsAvailable;
    }

    /**
     * Enhances a finalized in-memory Trip with grounded LLM copywriting.
     * Never throws exceptions; falls back to template reasons if disabled or failed.
     *
     * @return true if LLM narrative was generated and applied, false if template fallback was used.
     */
    public boolean enhanceTrip(Map<String, Object> trip,
                               TravelConstraints constraints,
                               AppliedPreferencesSnapshot appliedPreferences) {
        if (trip == null || trip.isEmpty()) {
            return false;
        }

        // Feature flag or credentials missing -> keep template reasons
        if (!enabled) {
            trip.put("explanationSource", PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
            return false;
        }
        if (!credentialsAvailable) {
            log.info("[GroundedNarrative] API Key 未配置，降级为模板理由");
            trip.put("explanationSource", PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
            return false;
        }

        CompletableFuture<String> modelFuture = null;
        try {
            // Build prompt
            String userPrompt = buildUserPrompt(trip, constraints, appliedPreferences);
            if (userPrompt == null || userPrompt.isBlank()) {
                trip.put("explanationSource", PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
                return false;
            }

            // Call model with timeout
            modelFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return modelClient.complete(SYSTEM_PROMPT, userPrompt);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, modelExecutor);
            String response = modelFuture.get(timeoutMs, TimeUnit.MILLISECONDS);

            // Parse response
            NarrativeOutput output = parseOutput(response);
            if (output == null) {
                log.warn("[GroundedNarrative] 模型响应解析失败，降级为模板理由");
                trip.put("explanationSource", PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
                return false;
            }

            // Evidence-based assertion verification
            if (!validateAssertions(output, userPrompt, trip)) {
                log.warn("[GroundedNarrative] 发现未经输入证据支持的绝对化断言，触发安全降级到模板理由");
                trip.put("explanationSource", PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
                return false;
            }

            // Apply narrative safely
            applyNarrative(trip, output);
            trip.put("explanationSource", PlannerVersionMetadata.EXPLANATION_SOURCE_LLM_GROUNDED);
            log.info("[GroundedNarrative] 成功生成并注入 LLM 真实推荐理由");
            return true;

        } catch (RejectedExecutionException e) {
            log.warn("[GroundedNarrative] 模型调用过载，降级为模板理由");
            trip.put("explanationSource", PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
            return false;
        } catch (TimeoutException e) {
            if (modelFuture != null) modelFuture.cancel(true);
            log.warn("[GroundedNarrative] 模型调用超时 ({}ms)，降级为模板理由", timeoutMs);
            trip.put("explanationSource", PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
            return false;
        } catch (InterruptedException e) {
            if (modelFuture != null) modelFuture.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("[GroundedNarrative] 模型调用被中断，降级为模板理由");
            trip.put("explanationSource", PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
            return false;
        } catch (Exception e) {
            log.warn("[GroundedNarrative] 模型调用异常: {}，降级为模板理由", e.getMessage());
            trip.put("explanationSource", PlannerVersionMetadata.EXPLANATION_SOURCE_TEMPLATE);
            return false;
        }
    }

    private String buildUserPrompt(Map<String, Object> trip,
                                   TravelConstraints constraints,
                                   AppliedPreferencesSnapshot appliedPreferences) {
        try {
            Map<String, Object> context = new LinkedHashMap<>();

            // 1. User preferences & constraints
            Map<String, Object> prefs = new LinkedHashMap<>();
            if (constraints != null) {
                if (constraints.getDurationDays() > 0) prefs.put("durationDays", constraints.getDurationDays());
                if (constraints.getCompanions() != null && !"未提供".equals(constraints.getCompanions())) prefs.put("companions", constraints.getCompanions());
                if (constraints.getWalkingTolerance() != null && !"未提供".equals(constraints.getWalkingTolerance())) prefs.put("walkingTolerance", constraints.getWalkingTolerance());
                if (constraints.getInterests() != null && !constraints.getInterests().isEmpty()) prefs.put("interests", constraints.getInterests());
                if (constraints.getBudget() != null && !"未提供".equals(constraints.getBudget())) prefs.put("budget", constraints.getBudget());
                if (constraints.getTransportPreference() != null && !"未提供".equals(constraints.getTransportPreference())) prefs.put("transportPreference", constraints.getTransportPreference());
                if (constraints.getDietPreference() != null && !"未提供".equals(constraints.getDietPreference())) prefs.put("dietPreference", constraints.getDietPreference());
            }
            if (appliedPreferences != null) {
                prefs.put("appliedPreferences", appliedPreferences.asMap());
            }
            context.put("userPreferences", prefs);

            // 2. Global route status & metadata
            context.put("routeDataStatus", trip.getOrDefault("routeDataStatus", "ESTIMATED"));
            context.put("sourceMode", trip.getOrDefault("sourceMode", "演示回退模式"));

            // 3. Structured itinerary stops
            List<Map<String, Object>> stopList = new ArrayList<>();
            Object daysObj = trip.get("days");
            if (daysObj instanceof List<?> days) {
                for (Object dayObj : days) {
                    if (dayObj instanceof Map<?, ?> dayMap) {
                        int dayNum = dayMap.get("day") instanceof Number n ? n.intValue() : 1;
                        Object stopsObj = dayMap.get("stops");
                        if (stopsObj instanceof List<?> stops) {
                            for (Object stopObj : stops) {
                                if (stopObj instanceof Map<?, ?> stopMap) {
                                    Map<String, Object> s = new LinkedHashMap<>();
                                    s.put("day", dayNum);
                                    s.put("stopId", stopMap.get("id"));
                                    s.put("name", stopMap.get("name"));
                                    s.put("district", stopMap.get("district"));
                                    s.put("time", stopMap.get("time"));
                                    s.put("duration", stopMap.get("duration"));
                                    s.put("walkDifficulty", stopMap.get("walkDifficulty"));
                                    s.put("indoor", stopMap.get("indoor"));
                                    s.put("ticket", stopMap.get("ticket"));
                                    s.put("summary", stopMap.get("summary"));

                                    // Score breakdown & reason codes
                                    Object scoreObj = stopMap.get("scoreBreakdown");
                                    if (scoreObj instanceof Map<?, ?> sm) {
                                        s.put("reasonCodes", sm.get("reasonCodes"));
                                        s.put("scoreTotal", sm.get("total"));
                                    }

                                    // Route cost from previous
                                    Object routeObj = stopMap.get("resolvedRouteCost");
                                    if (routeObj == null) routeObj = stopMap.get("estimatedRouteCost");
                                    if (routeObj instanceof Map<?, ?> rm) {
                                        s.put("travelTimeFromPrevious", rm.get("durationText"));
                                        s.put("routeStatus", rm.get("status"));
                                    }

                                    stopList.add(s);
                                }
                            }
                        }
                    }
                }
            }
            context.put("itineraryStops", stopList);

            return objectMapper.writeValueAsString(context);
        } catch (Exception e) {
            log.warn("[GroundedNarrative] 构建 Prompt 异常: {}", e.getMessage());
            return null;
        }
    }

    private NarrativeOutput parseOutput(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            String jsonText = response.trim();
            Matcher matcher = JSON_OBJECT_PATTERN.matcher(jsonText);
            if (matcher.find()) {
                jsonText = matcher.group();
            }
            JsonNode root = objectMapper.readTree(jsonText);
            String tripSummary = root.has("tripSummary") && !root.get("tripSummary").isNull()
                    ? root.get("tripSummary").asText().trim() : "";

            Map<String, String> stopReasons = new LinkedHashMap<>();
            JsonNode reasonsNode = root.get("stopReasons");
            if (reasonsNode != null && reasonsNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = reasonsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getValue() != null && !entry.getValue().isNull()) {
                        String r = entry.getValue().asText().trim();
                        if (!r.isBlank()) {
                            stopReasons.put(entry.getKey(), r);
                        }
                    }
                }
            }
            return new NarrativeOutput(tripSummary, stopReasons);
        } catch (Exception e) {
            log.warn("[GroundedNarrative] JSON 解析异常: {}", e.getMessage());
            return null;
        }
    }

    private boolean validateAssertions(NarrativeOutput output, String userPromptJson, Map<String, Object> trip) {
        if (output == null) return false;

        // 1. Check tripSummary against the entire input prompt JSON
        if (output.tripSummary() != null && !output.tripSummary().isBlank()) {
            for (String kw : STRICT_ASSERTION_KEYWORDS) {
                if (output.tripSummary().contains(kw) && !userPromptJson.contains(kw)) {
                    log.warn("[GroundedNarrative] tripSummary 包含未经输入支持的绝对化断言 '{}': {}", kw, output.tripSummary());
                    return false;
                }
            }
        }

        // 2. Check each stopReason against the specific stop's evidence (for existing stops in trip)
        Map<String, String> stopReasons = output.stopReasons();
        if (stopReasons != null && !stopReasons.isEmpty()) {
            Map<String, String> stopEvidenceMap = extractStopEvidenceTexts(trip);
            for (Map.Entry<String, String> entry : stopReasons.entrySet()) {
                String stopId = entry.getKey();
                if (!stopEvidenceMap.containsKey(stopId)) {
                    // Unknown stopId is safely ignored in applyNarrative
                    continue;
                }
                String reason = entry.getValue();
                if (reason == null || reason.isBlank()) continue;
                String stopEvidence = stopEvidenceMap.getOrDefault(stopId, "");
                for (String kw : STRICT_ASSERTION_KEYWORDS) {
                    if (reason.contains(kw) && !stopEvidence.contains(kw)) {
                        log.warn("[GroundedNarrative] stopReason[{}] 包含未经输入支持的绝对化断言 '{}': {}", stopId, kw, reason);
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private Map<String, String> extractStopEvidenceTexts(Map<String, Object> trip) {
        Map<String, String> map = new LinkedHashMap<>();
        if (trip == null) return map;
        Object daysObj = trip.get("days");
        if (daysObj instanceof List<?> days) {
            for (Object dayObj : days) {
                if (dayObj instanceof Map<?, ?> dayMap) {
                    Object stopsObj = dayMap.get("stops");
                    if (stopsObj instanceof List<?> stops) {
                        for (Object stopObj : stops) {
                            if (stopObj instanceof Map<?, ?> stopMap) {
                                String stopId = String.valueOf(stopMap.get("id"));
                                StringBuilder sb = new StringBuilder();
                                stopMap.forEach((k, v) -> {
                                    if (v != null) {
                                        sb.append(v).append(" ");
                                    }
                                });
                                map.put(stopId, sb.toString());
                            }
                        }
                    }
                }
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private void applyNarrative(Map<String, Object> trip, NarrativeOutput output) {
        if (output == null) return;

        // 1. Update tripSummary if non-empty
        if (output.tripSummary() != null && !output.tripSummary().isBlank()) {
            trip.put("summary", output.tripSummary());
            trip.put("tripSummary", output.tripSummary());
        }

        // 2. Update stop reasons only for valid existing stop IDs
        Map<String, String> stopReasons = output.stopReasons();
        if (stopReasons == null || stopReasons.isEmpty()) return;

        Object daysObj = trip.get("days");
        if (daysObj instanceof List<?> days) {
            for (Object dayObj : days) {
                if (dayObj instanceof Map<?, ?> dayMap) {
                    Object stopsObj = dayMap.get("stops");
                    if (stopsObj instanceof List<?> stops) {
                        for (Object stopObj : stops) {
                            if (stopObj instanceof Map<?, ?> stopMap) {
                                Map<String, Object> mutableStop = (Map<String, Object>) stopMap;
                                String stopId = String.valueOf(mutableStop.get("id"));
                                if (stopReasons.containsKey(stopId)) {
                                    String newReason = stopReasons.get(stopId);
                                    if (newReason != null && !newReason.isBlank()) {
                                        mutableStop.put("recommendationReason", newReason);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public record NarrativeOutput(String tripSummary, Map<String, String> stopReasons) {}
}
