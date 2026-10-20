package com.ai.guide.domain.memory.service;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.memory.port.MemoryRepositoryPort;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.preferences.model.UserPreferencesPatch;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.service.PreferencesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.ai.guide.domain.language.IntentLanguagePolicy;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight, user-confirmed travel memory store with dual-track (rules + LLM smart-path) preference extraction. */
@Service
public class TravelMemoryService {
    private static final Logger log = LoggerFactory.getLogger(TravelMemoryService.class);

    private static final String LLM_MEMORY_SYSTEM_PROMPT = """
            你是“渝游智策”的旅行者个人画像与偏好提炼专家。
            请分析用户的当前对话陈述，判断是否包含用户的【长效/通用个人旅行习惯、偏好或禁忌】。

            研判原则：
            1. 长期/通用偏好（判定为 true）：
               - 饮食口味/偏好/忌口/过敏（如：表达喜欢或避开某类饮食、说明明确禁忌或过敏信息）
               - 体力节奏/步行耐受/无障碍（如：“我膝盖不好不能多爬坡”、“有婴儿车要平路”、“体力有限不要特种兵”、“精力充沛想多逛几个”）
               - 出行方式/作息习惯/天气偏好（如：“出门只打车”、“夜猫子起不来床”、“特别怕晒受不了大太阳”、“下雨天更偏向室内展馆”）
               - 兴趣追求/同行人群（如：“重度摄影爱好者要绝佳机位”、“带爸妈出来玩以舒适为主”、“独爱古建筑和博物馆”）
            2. 临时单次行程操作或普通问答（判定为 false）：
               - 如：“今天先去某个景点”、“把这个站点换掉”、“查看景点门票”、“查询天气”、“取消当前方案”、“导航到该地点”

            输出规则（必须严格仅输出单个合法 JSON 对象，严禁包含任何 Markdown 格式或解释）：
            {
              "hasPreference": true 或 false,
              "category": "DIET | PACE | ACCESSIBILITY | TRANSPORT | BUDGET | WEATHER | TIME | STAY | COMPANION | INTEREST | CUSTOM",
              "content": "20字以内的规范偏好陈述，保留用户实际提到的饮食、体力、文化或出行习惯",
              "confidence": 0.75 到 1.0 的浮点数
            }
            若未识别到长期个人偏好，输出：
            {"hasPreference": false}
            """;

    private final MemoryRepositoryPort memoryRepository;
    private final PreferencesService preferencesService;
    private final LanguageModelPort languageModel;
    private final ObjectMapper objectMapper;
    private final MemoryPreferenceProjector preferenceProjector;
    @org.springframework.beans.factory.annotation.Value("${memory.prompt.max-chars:6000}")
    private int promptMaxChars = 6000;
    @org.springframework.beans.factory.annotation.Value("${memory.content.max-chars:6000}")
    private int contentMaxChars = 6000;
    public static final class MemoryContextOverflowException extends IllegalArgumentException {
        public MemoryContextOverflowException() {
            super("安全限制与专属指令超过上下文预算，请先精简画像；不会截断或忽略这些限制。");
        }
    }

    private String boundedPrompt(String userId) {
        if (memoryRepository == null) return "";
        List<TravelMemory> memories = memoryRepository.findConfirmedByUserIdOrderByUpdatedAtDesc(userId);
        var critical = new java.util.LinkedHashSet<String>();
        var optional = new java.util.LinkedHashSet<String>();
        for (TravelMemory item : memories) {
            String content = item.content() == null ? "" : item.content().trim();
            if (content.isBlank()) continue;
            TravelMemory projected = preferenceProjector.project(item);
            // The taxonomy only projects complete, recognised presentation preferences;
            // any extra restriction or unknown language stays as raw TEXT. Unknown DIET
            // statements must not lose an allergy simply because a keyword was not recognised.
            boolean knownPreference = projected.preferenceKey() != null
                    && !projected.preferenceKey().startsWith("TEXT:");
            boolean safety = java.util.Set.of("ACCESSIBILITY", "PACE", "COMPANION").contains(item.category())
                    || "DIET".equals(item.category()) && !knownPreference
                    || content.matches("(?s).*(?:过敏|忌口|不|勿|禁止|避免|轮椅|台阶|膝盖|禁忌).*" );
            if (safety) critical.add(content);
            else optional.add(projected.preferenceSummary().trim());
        }
        UserPreferences preferences = preferencesService == null ? null : preferencesService.get(userId);
        Object customized = preferences == null ? null : manualPrompt(new LinkedHashMap<>(preferences.legacyMetadata()));
        if (customized instanceof String s && !s.isBlank()) critical.add(s.trim());
        int budget = Math.max(1000, promptMaxChars);
        StringBuilder result = new StringBuilder("【旅行偏好：安全限制与专属指令优先】\n");
        for (String content : critical) result.append("• ").append(content).append('\n');
        if (result.length() > budget - 150) {
            throw new MemoryContextOverflowException();
        }
        int omitted = 0;
        for (String content : optional) {
            if (result.length() + content.length() + 3 <= budget - 150) result.append("• ").append(content).append('\n');
            else omitted++;
        }
        if (omitted > 0) result.append("（另有").append(omitted).append("项偏好未注入本轮上下文，完整内容仍保存在画像中；不得声称已考虑全部偏好。）\n");
        return result.toString();
    }

    /** Lightweight constructor retained for pure candidate unit tests. */
    public TravelMemoryService(MemoryRepositoryPort memoryRepository) {
        this(memoryRepository, null, null, null);
    }

    public TravelMemoryService(MemoryRepositoryPort memoryRepository, PreferencesService preferencesService) {
        this(memoryRepository, preferencesService, null, null);
    }

    public TravelMemoryService(MemoryRepositoryPort memoryRepository,
                               PreferencesService preferencesService,
                               @Autowired(required = false) LanguageModelPort languageModel,
                               @Autowired(required = false) ObjectMapper objectMapper) {
        this(memoryRepository, preferencesService, languageModel, objectMapper, new MemoryPreferenceProjector(null));
    }

    @Autowired
    public TravelMemoryService(MemoryRepositoryPort memoryRepository,
                               PreferencesService preferencesService,
                               @Autowired(required = false) LanguageModelPort languageModel,
                               @Autowired(required = false) ObjectMapper objectMapper,
                               MemoryPreferenceProjector preferenceProjector) {
        this.memoryRepository = memoryRepository;
        this.preferencesService = preferencesService;
        this.languageModel = languageModel;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.preferenceProjector = preferenceProjector;
    }

    public List<TravelMemory> list(String userId) {
        return listPage(userId, 50, 0);
    }

    public List<TravelMemory> listPage(String userId, int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new IllegalArgumentException("记忆分页要求limit为1至100，offset不得为负数");
        }
        if (!validUser(userId) || memoryRepository == null) return List.of();
        return memoryRepository.findConfirmedByUserIdPaged(userId, limit, offset)
                .stream().map(preferenceProjector::project).toList();
    }

    /**
     * 废除 7 天过期限制与清理，记忆长期永久保存。
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 5 * 60 * 1000L)
    public void cleanupExpiredMemories() {
        // 废除 7 天过期限制与清理，记忆长期永久保存
    }

    public Candidate suggest(String message) {
        String text = clean(message, 400);
        if (text.isBlank() || isCurrentTripOnly(text)) return null;

        // 1. 快速规则直通车（Fast-path）：高频场景 0ms 瞬间命中
        if (hasStablePersonalSignal(text)) {
            Candidate fast = matchRuleCandidate(text);
            if (fast != null) return fast;
        }

        // 2. 深度大模型提炼（Smart-path）：复杂/隐晦长句智能槽位提取与规范化
        if (shouldTriggerLlmExtraction(text)) {
            Candidate smart = extractWithLlm(text);
            if (smart != null) return smart;
        }

        return null;
    }

    /**
     * 高频偏好场景确定性规则库（0ms 秒级直通，涵盖核心旅行生活高频画像）
     */
    Candidate matchRuleCandidate(String text) {
        boolean dietSignal = hasAny(text, preferenceProjector.vocabulary("dietRestrictionKeywords"));
        boolean negationException = hasAny(text, preferenceProjector.vocabulary("dietNegationExceptions"));
        if (dietSignal && !negationException && (text.contains("吃") || text.contains("喝") || matchPositiveDiet(text))) {
            return new Candidate("DIET", text, 0.96);
        }
        // 无障碍与适老化
        if (hasAny(text, preferenceProjector.vocabulary("memoryAccessibility"))) {
            return new Candidate("ACCESSIBILITY", "出行需要无障碍、少台阶与便于休息的安排", 0.95);
        }

        // Food preference categories and their aliases/summaries are taxonomy assets.
        var dietFacet = preferenceProjector.matchingFacet("DIET", text);
        if (dietFacet != null) return new Candidate("DIET", dietFacet.summary(), 0.92);

        // 体力节奏：少走路、平路、慢节奏
        if (hasAny(text, preferenceProjector.vocabulary("memoryPaceLow"))) {
            return new Candidate("PACE", "偏好少走路，优先平路、电梯与短步行", 0.93);
        }

        // 体力节奏：特种兵、高密度、深度游
        if (hasAny(text, preferenceProjector.vocabulary("memoryPaceHigh"))) {
            return new Candidate("PACE", "偏好高密度充实游览，体力充沛，愿意多走多看", 0.89);
        }

        // 交通偏好：打车、网约车
        if (hasAny(text, preferenceProjector.vocabulary("memoryTransportTaxi"))) {
            return new Candidate("TRANSPORT", "出行时优先打车或网约车", 0.91);
        }

        // 交通偏好：地铁、公交、轻轨
        if (hasAny(text, preferenceProjector.vocabulary("memoryTransportTransit"))) {
            return new Candidate("TRANSPORT", "出行时更愿意使用公共交通", 0.86);
        }

        // 预算：经济型、性价比、穷游
        if (hasAny(text, preferenceProjector.vocabulary("memoryBudgetLow"))) {
            return new Candidate("BUDGET", "出行时重视预算与性价比，优先平价与免费景点", 0.85);
        }

        // 预算：高端品质
        if (hasAny(text, preferenceProjector.vocabulary("memoryBudgetHigh"))) {
            return new Candidate("BUDGET", "注重出行品质与舒适度，偏好高端体验", 0.88);
        }

        // 天气体感：怕晒、避雨、室内
        if (hasAny(text, preferenceProjector.vocabulary("memoryWeather"))) {
            return new Candidate("WEATHER", "行程更在意天气体感，优先阴凉、室内或可避雨安排", 0.87);
        }

        // 作息时间：晚起、从容、夜猫子
        if (hasAny(text, preferenceProjector.vocabulary("memoryTime"))) {
            return new Candidate("TIME", "出行节奏不宜太早，偏好从容晚起安排", 0.84);
        }

        // 住宿倾向：安静、江景、近地铁
        if (hasAny(text, preferenceProjector.vocabulary("memoryStay"))) {
            return new Candidate("STAY", "住宿更看重安静、江景与交通便利", 0.82);
        }

        // 同行人照顾：亲子、儿童
        if (hasAny(text, preferenceProjector.vocabulary("memoryCompanionChild"))) {
            return new Candidate("COMPANION", "出行时常需要兼顾亲子与儿童体验", 0.84);
        }

        // 同行人照顾：父母、长辈、老年人
        if (hasAny(text, preferenceProjector.vocabulary("memoryCompanionSenior"))) {
            return new Candidate("COMPANION", "出行时常需要兼顾长辈的体力与舒适度", 0.84);
        }

        // 同行人照顾：情侣、蜜月、朋友
        if (hasAny(text, preferenceProjector.vocabulary("memoryCompanionFriends"))) {
            return new Candidate("COMPANION", "通常以情侣或朋友结伴方式安排行程", 0.84);
        }

        // 兴趣体验：拍照、摄影、绝佳机位
        if (hasAny(text, preferenceProjector.vocabulary("memoryInterestPhotography"))) {
            return new Candidate("INTEREST", "旅行时重视摄影出片与绝佳机位视角", 0.85);
        }

        // 兴趣体验：通用夜景偏好，不自动推断成特定城市体验
        if (hasPositivePreferenceMention(text, preferenceProjector.vocabulary("memoryInterestNight"))) {
            return new Candidate("INTEREST", "偏好夜景观赏体验", 0.85);
        }

        // 兴趣体验：人文历史、文创老街、展览古迹
        if (hasAny(text, preferenceProjector.vocabulary("memoryInterestHistory"))) {
            return new Candidate("INTEREST", "偏爱人文历史与室内文化场馆", 0.87);
        }

        // 兴趣体验：自然山水、户外风光
        if (hasAny(text, preferenceProjector.vocabulary("memoryInterestNature"))) {
            return new Candidate("INTEREST", "偏爱自然山水、高山峡谷与户外风光", 0.82);
        }

        return null;
    }

    /**
     * 前置门禁粗筛：过滤纯指令与地点问答，仅放行具备个人主观表述特征的语句
     */
    private boolean shouldTriggerLlmExtraction(String text) {
        if (text == null || text.length() < 3) return false;
        // 排除明显的纯规划指令与位置问答
        if (text.matches("^(换掉|删除|去掉|加上|增加|换一个|换成|选方案|怎么走|几点开门|多少钱|导航到|去哪|查一下).*")) {
            return false;
        }
        // 包含主观倾向或生活人称线索
        return text.matches(".*(我|我们|平时|总是|习惯|经常|容易|希望|喜欢|爱|想|偏爱|讨厌|受不了|怕|不能|不吃|忌|过敏|胃|腿|脚|累|带|爸妈|父母|长辈|孩子|娃|老人|出行|旅游|玩|吃|住|行).*");
    }

    /**
     * 异步大模型语义提炼（Smart-Path，超时熔断保护，不阻塞主流程）
     */
    private Candidate extractWithLlm(String text) {
        if (languageModel == null) return null;
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                    languageModel.completeText(List.of(
                            new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, LLM_MEMORY_SYSTEM_PROMPT),
                            new LanguageModelMessage(LanguageModelMessage.Role.USER, text)))
            );
            String raw = future.get(2500, TimeUnit.MILLISECONDS);
            if (raw == null || raw.isBlank()) return null;
            Matcher matcher = Pattern.compile("\\{.*\\}", Pattern.DOTALL).matcher(raw);
            if (!matcher.find()) return null;
            JsonNode root = objectMapper.readTree(matcher.group(0));
            if (!root.path("hasPreference").asBoolean(false)) return null;
            String category = normalizeCategory(root.path("category").asText("CUSTOM"));
            String content = clean(root.path("content").asText(""), 240);
            double confidence = root.path("confidence").asDouble(0.85);
            if (content.isBlank() || confidence < 0.70) return null;
            log.info("[TravelMemory] LLM extracted candidate: category={}, content={}, confidence={}", category, content, confidence);
            return new Candidate(category, content, Math.min(1.0, Math.max(0.70, confidence)));
        } catch (Exception e) {
            log.debug("[TravelMemory] LLM smart-path skipped or timed out: {}", e.getMessage());
            return null;
        }
    }

    private boolean matchPositiveDiet(String text) {
        // Ask the existing rule path only for category, retaining the negative source itself.
        String positive = text.replace("不喜欢", "喜欢").replace("不爱", "爱").replace("讨厌", "喜欢");
        if (positive.equals(text)) return false;
        Candidate candidate = matchRuleCandidate(positive);
        return candidate != null && "DIET".equals(candidate.category());
    }

    private int spicePolarity(String content) {
        if (content == null) return 0;
        String positive = content;
        boolean negationException = hasAny(content, preferenceProjector.vocabulary("dietNegationExceptions"));
        if (negationException) {
            for (String exception : preferenceProjector.vocabulary("dietNegationExceptions")) positive = positive.replace(exception, "");
            return hasAny(positive, preferenceProjector.vocabulary("spicyPositiveAliases")) ? 1 : 0;
        }
        if (hasAny(content, preferenceProjector.vocabulary("spicyNegativeAliases"))) return -1;
        positive = IntentLanguagePolicy.positiveText(positive);
        return hasAny(positive, preferenceProjector.vocabulary("spicyPositiveAliases")) ? 1 : 0;
    }

    private void handleConflictingMemories(String userId, String newContent, String excludedId) {
        if (!validUser(userId) || newContent == null || memoryRepository == null) return;
        for (Map<String,Object> row : memoryRepository.findActiveIdAndContent(userId)) {
            String id = String.valueOf(row.get("id"));
            if (id.equals(excludedId)) continue;
            String old = String.valueOf(row.get("content"));
            int before = spicePolarity(old), after = spicePolarity(newContent);
            boolean conflict = before != 0 && after != 0 && before != after;
            String oldPositive = IntentLanguagePolicy.positiveText(old);
            String newPositive = IntentLanguagePolicy.positiveText(newContent);
            conflict |= hasAny(newContent, preferenceProjector.vocabulary("lowWalkingPreferenceAliases"))
                    && hasAny(oldPositive, preferenceProjector.vocabulary("highWalkingPreferenceAliases"));
            conflict |= hasAny(newPositive, preferenceProjector.vocabulary("highWalkingPreferenceAliases"))
                    && hasAny(old, preferenceProjector.vocabulary("lowWalkingPreferenceAliases"));
            if (conflict) {
                var kept = new ArrayList<String>();
                for (String clause : old.split("[，,；;。\\n、]+")) {
                    boolean conflicting = spicePolarity(clause) != 0 && after != 0 && spicePolarity(clause) != after;
                    conflicting |= hasAny(newContent, preferenceProjector.vocabulary("lowWalkingPreferenceAliases")) && hasAny(IntentLanguagePolicy.positiveText(clause), preferenceProjector.vocabulary("highWalkingPreferenceAliases"));
                    conflicting |= hasAny(newPositive, preferenceProjector.vocabulary("highWalkingPreferenceAliases")) && hasAny(clause, preferenceProjector.vocabulary("lowWalkingPreferenceAliases"));
                    if (!conflicting) { if (!clause.isBlank()) kept.add(clause.trim()); continue; }
                    // Only retire a standalone preference clause. Unknown residual meaning must survive.
                    String residual = clause;
                    for (String word : allConflictPreferenceWords()) residual = residual.replace(word, "");
                    residual = preferenceProjector.removePresentationWords(residual).replaceAll("[\\s\\p{P}]+", "");
                    if (!residual.isBlank()) throw new IllegalArgumentException("旧记忆包含相互冲突的偏好和其他信息，请先编辑这条记忆，保留过敏、同行人与其他限制：" + old);
                }
                if (kept.isEmpty()) memoryRepository.softDelete(id, userId, System.currentTimeMillis());
                else memoryRepository.updateContent(id, userId, String.join("，", kept), System.currentTimeMillis());
            }
        }
    }

    private boolean hasAny(String text, List<String> words) {
        return text != null && words.stream().filter(word -> word != null && !word.isBlank()).anyMatch(text::contains);
    }

    private boolean hasPositivePreferenceMention(String text, List<String> words) {
        if (text == null) return false;
        List<String> negations = preferenceProjector.vocabulary("preferenceNegationMarkers");
        for (String word : words) {
            if (word == null || word.isBlank()) continue;
            int from = 0;
            int index;
            while ((index = text.indexOf(word, from)) >= 0) {
                String preceding = text.substring(Math.max(0, index - 12), index);
                boolean negated = negations.stream().anyMatch(marker -> {
                    int markerIndex = preceding.lastIndexOf(marker);
                    return markerIndex >= 0 && preceding.length() - markerIndex - marker.length() <= 4;
                });
                if (!negated) return true;
                from = index + word.length();
            }
        }
        return false;
    }

    private List<String> allConflictPreferenceWords() {
        var words = new java.util.LinkedHashSet<String>();
        for (String key : List.of("spicyPositiveAliases", "spicyNegativeAliases", "lowWalkingPreferenceAliases", "highWalkingPreferenceAliases", "dietNegationExceptions")) {
            words.addAll(preferenceProjector.vocabulary(key));
        }
        return words.stream().sorted(java.util.Comparator.comparingInt(String::length).reversed()).toList();
    }

    private <T> T atomic(Supplier<T> action) {
        return memoryRepository == null ? action.get() : memoryRepository.runInTransaction(action);
    }

    public TravelMemory confirm(String userId, String content, String category, String sourceType) {
        return confirm(userId, content, category, sourceType, "");
    }

    public TravelMemory confirm(String userId, String content, String category, String sourceType, String sourceRef) {
        return atomic(() -> confirmAtomic(userId, content, category, sourceType, sourceRef));
    }

    private TravelMemory confirmAtomic(String userId, String content, String category, String sourceType, String sourceRef) {
        requireUser(userId);
        if (memoryRepository == null) throw new IllegalArgumentException("记忆服务暂不可用");
        String safeContent = completeText(content, contentMaxChars, "记忆内容");
        if (safeContent.isBlank()) throw new IllegalArgumentException("记忆内容不能为空");
        String safeCategory = normalizeCategory(category);
        long now = System.currentTimeMillis();
        handleConflictingMemories(userId, safeContent, null);
        TravelMemory existing = memoryRepository.findSameConfirmed(userId, safeCategory, safeContent);
        if (existing != null) {
            memoryRepository.touchUpdatedAt(existing.id(), userId, now);
            synthesizePrependPrompt(userId, false);
            return preferenceProjector.project(new TravelMemory(existing.id(), existing.category(), existing.content(), existing.sourceType(), existing.sourceRef(), existing.status(), existing.confidence(), existing.createdAt(), now));
        }
        String id = "mem-" + UUID.randomUUID();
        String source = "USER_EDIT".equalsIgnoreCase(sourceType) ? "USER_EDIT" : "CHAT_CONFIRMED";
        String safeSourceRef = sourceRef == null ? "" : clean(sourceRef, 120);
        TravelMemory created = new TravelMemory(id, safeCategory, safeContent, source, safeSourceRef, "CONFIRMED", source.equals("USER_EDIT") ? 1.0 : 0.9, now, now);
        memoryRepository.save(created, userId);
        synthesizePrependPrompt(userId, false);
        return preferenceProjector.project(created);
    }

    public TravelMemory update(String userId, String id, String content, String category) {
        return atomic(() -> updateAtomic(userId, id, content, category));
    }

    private TravelMemory updateAtomic(String userId, String id, String content, String category) {
        requireUser(userId);
        if (memoryRepository == null || !memoryRepository.existsConfirmed(id, userId)) {
            throw new IllegalArgumentException("旅行记忆不存在或无权修改");
        }
        String safeContent = completeText(content, contentMaxChars, "记忆内容");
        if (safeContent.isBlank()) throw new IllegalArgumentException("记忆内容不能为空");
        handleConflictingMemories(userId, safeContent, id);
        int changed = memoryRepository.updateContentAndCategory(id, userId, safeContent, normalizeCategory(category), System.currentTimeMillis());
        if (changed == 0) throw new IllegalArgumentException("旅行记忆不存在或无权修改");
        synthesizePrependPrompt(userId, false);
        return list(userId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    public void delete(String userId, String id) {
        atomic(() -> { deleteAtomic(userId, id); return null; });
    }

    private void deleteAtomic(String userId, String id) {
        requireUser(userId);
        if (memoryRepository != null) {
            memoryRepository.softDelete(id, userId, System.currentTimeMillis());
        }
        synthesizePrependPrompt(userId, false);
    }

    /** Stores one concise user preference cue, never a full transcript or assistant reply. */
    public void captureObservation(String userId, String content, String sourceSession) {
        requireUser(userId);
        if (memoryRepository == null) return;
        String cue = clean(content, 400);
        if (cue.isBlank() || suggest(cue) == null) return;
        long now = System.currentTimeMillis();
        String session = clean(sourceSession, 120);
        if (memoryRepository.hasPendingObservation(userId, cue)) return;
        memoryRepository.insertObservation("obs-" + UUID.randomUUID(), userId, cue, session, now);
    }

    public List<MemoryCandidate> candidates(String userId) {
        requireUser(userId);
        if (memoryRepository == null) return List.of();
        return memoryRepository.findPendingCandidatesByUserId(userId, 12);
    }

    public TravelMemory confirmCandidate(String userId, String candidateId) {
        return atomic(() -> confirmCandidateAtomic(userId, candidateId));
    }

    private TravelMemory confirmCandidateAtomic(String userId, String candidateId) {
        requireUser(userId);
        if (memoryRepository == null) throw new IllegalArgumentException("记忆服务暂不可用");
        List<MemoryCandidate> rows = memoryRepository.findPendingCandidateByIdAndUserId(candidateId, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("记忆候选不存在或已处理");
        MemoryCandidate candidate = rows.get(0);
        int claimed = memoryRepository.updateCandidateStatus(candidateId, userId, "CONFIRMED", System.currentTimeMillis());
        if (claimed != 1) throw new IllegalArgumentException("记忆候选不存在或已处理");
        return confirm(userId, candidate.content(), candidate.category(), "CHAT_CONFIRMED", candidate.sourceRef());
    }

    public void dismissCandidate(String userId, String candidateId) {
        requireUser(userId);
        if (memoryRepository != null) {
            memoryRepository.updateCandidateStatus(candidateId, userId, "DISMISSED", System.currentTimeMillis());
        }
    }

    /**
     * Genuine background review: it reads only compact user cues that were
     * explicitly selected for memory review. It never imports a memory itself.
     */
    @Scheduled(fixedDelay = 60 * 60 * 1000L, initialDelay = 60 * 1000L)
    public void reviewQueuedObservations() {
        if (memoryRepository == null || preferencesService == null) return;
        List<String> users = memoryRepository.findDistinctPendingObservationUserIds();
        for (String userId : users) {
            try { reviewUserObservations(userId); } catch (RuntimeException ignored) { /* retry on the next scheduled pass */ }
        }
    }

    private void reviewUserObservations(String userId) {
        if (!Boolean.TRUE.equals(preferencesService.get(userId).legacyMetadata().get("travelMemoryEnabled"))) return;
        List<MemoryRepositoryPort.ObservationRecord> observations = memoryRepository.findPendingObservationsByUserId(userId, 60);
        Map<String,Candidate> detected = new LinkedHashMap<>();
        for (var observation : observations) detected.put(observation.id(), suggest(observation.content()));
        // Extraction may call a model. Only DB writes and the projection CAS belong in the transaction.
        atomic(() -> {
            UserPreferences preferences = preferencesService.get(userId);
            Map<String,Object> metadata = new LinkedHashMap<>(preferences.legacyMetadata());
            if (!Boolean.TRUE.equals(metadata.get("travelMemoryEnabled"))) return null;
            long now = System.currentTimeMillis();
            for (var observation : observations) {
                int claimed = memoryRepository.updateObservationStatus(observation.id(), userId, "REVIEWED");
                if (claimed != 1) continue;
                Candidate candidate = detected.get(observation.id());
                if (candidate == null) continue;
                if (!memoryRepository.hasPendingCandidate(userId, candidate.category(), candidate.content())) {
                    memoryRepository.insertCandidate("mem-candidate-" + UUID.randomUUID(), userId, candidate.category(), candidate.content(), clean(observation.content(), 120), candidate.confidence(), now, now);
                }
            }
            metadata.put("travelMemoryLastReviewAt", now);
            preferencesService.merge(userId, new UserPreferencesPatch(null,null,null,null,null,null,null,metadata,preferences.revision()), preferences.revision());
            return null;
        });
    }

    /**
     * 根据已确认记忆的分类与原文生成前置提示词（Prepend Planning Prompt）。
     * 冲突记忆由确认流程处理，画像只消费仍为 CONFIRMED 的记录，不丢弃未知词汇。
     * 同时保留用户之前手动定制过的专属指令（userCustomizedPrompt）。
     */
    public String synthesizePrependPrompt(String userId, boolean forceResynthesize) {
        if (!validUser(userId) || memoryRepository == null) return "";
        UserPreferences preferences = preferencesService != null ? preferencesService.get(userId) : null;
        Map<String, Object> metadata = preferences != null ? new LinkedHashMap<>(preferences.legacyMetadata()) : new LinkedHashMap<>();

        String userCustomized = manualPrompt(metadata);

        List<TravelMemory> items = memoryRepository.findConfirmedByUserId(userId);

        // Confirmed content is already the user's chosen preference. Group it by
        // category without reinterpreting it through a vocabulary whitelist.
        Map<String, java.util.LinkedHashSet<String>> grouped = new LinkedHashMap<>();
        for (TravelMemory item : items) {
            if (item.content() == null || item.content().isBlank()) continue;
            String heading = switch (normalizeCategory(item.category())) {
                case "DIET" -> "饮食喜好";
                case "INTEREST" -> "景观偏好";
                case "PACE", "TIME" -> "体力与作息";
                case "ACCESSIBILITY" -> "无障碍需求";
                case "COMPANION" -> "同行关照";
                case "TRANSPORT" -> "交通偏好";
                case "BUDGET" -> "预算偏好";
                case "WEATHER" -> "天气适应";
                case "STAY" -> "住宿偏好";
                default -> "个性化要求";
            };
            grouped.computeIfAbsent(heading, key -> new java.util.LinkedHashSet<>()).add(preferenceProjector.project(item).preferenceSummary().trim());
        }
        StringBuilder sb = new StringBuilder("【用户专属旅行偏好画像】\n");
        grouped.forEach((heading, contents) -> sb.append("• ").append(heading).append("：")
                .append(String.join("；", contents)).append("；\n"));

        String generated = sb.toString().trim();
        // Regenerating memory-derived content must not erase independently authored notes.
        if (!userCustomized.isBlank()) {
            sb.append("• 专属定制指令：").append(userCustomized.trim()).append("\n");
        }

        String synthesized = sb.toString().trim();
        if (synthesized.equals("【用户专属旅行偏好画像】")) {
            synthesized = "【用户专属旅行偏好画像】暂无特殊偏好，按常规经典游玩。";
        }

        if (preferencesService != null && preferences != null
                && (!synthesized.equals(preferences.legacyMetadata().get("profilePrependPrompt"))
                || !generated.equals(preferences.legacyMetadata().get("generatedPrependPrompt"))
                || !metadata.equals(preferences.legacyMetadata()))) {
            metadata.put("profilePrependPrompt", synthesized);
            metadata.put("generatedPrependPrompt", generated);
            metadata.put("manualPromptVersion", 2);
            metadata.put("memoryProjectionVersion", MemoryPreferenceProjector.PROJECTION_VERSION);
            metadata.put("promptLastSynthesizedAt", System.currentTimeMillis());
            preferencesService.merge(userId, new UserPreferencesPatch(null, null, null, null, null, null, null, metadata, preferences.revision()), preferences.revision());
        }

        return synthesized;
    }

    public String saveUserCustomizedPrompt(String userId, String fullPromptText) {
        return atomic(() -> saveManualPrompt(userId, fullPromptText));
    }

    private String saveManualPrompt(String userId, String fullPromptText) {
        if (!validUser(userId)) return fullPromptText;
        UserPreferences preferences = preferencesService != null ? preferencesService.get(userId) : null;
        Map<String, Object> metadata = preferences != null ? new LinkedHashMap<>(preferences.legacyMetadata()) : new LinkedHashMap<>();
        manualPrompt(metadata);
        String safePrompt = completePromptText(fullPromptText);
        // Compatibility for older editors: unchanged generated lines are not manual notes.
        String baseline = String.valueOf(metadata.getOrDefault("generatedPrependPrompt", ""));
        if (safePrompt.startsWith("【用户专属旅行偏好画像】") && !baseline.isBlank()) {
            var generatedLines = new java.util.HashSet<String>();
            for (String line : baseline.split("\\R")) generatedLines.add(promptLine(line));
            safePrompt = java.util.Arrays.stream(safePrompt.split("\\R"))
                    .filter(line -> !generatedLines.contains(promptLine(line)))
                    .map(line -> line.startsWith("• 专属定制指令:") ? line.substring("• 专属定制指令:".length()) : line)
                    .collect(java.util.stream.Collectors.joining("\n")).trim();
        }
        metadata.put("userCustomizedPrompt", safePrompt);
        metadata.put("manualPromptVersion", 2);
        metadata.put("manualPromptNeedsReview", false);
        metadata.put("promptLastEditedAt", System.currentTimeMillis());
        if (preferencesService != null && preferences != null) {
            preferencesService.merge(userId, new UserPreferencesPatch(null, null, null, null, null, null, null, metadata, preferences.revision()), preferences.revision());
        }
        synthesizePrependPrompt(userId, false);
        return safePrompt;
    }

    /** Ambiguous legacy full profiles are archived, never treated as an independent preference. */
    private String manualPrompt(Map<String, Object> metadata) {
        String manual = String.valueOf(metadata.getOrDefault("userCustomizedPrompt", ""));
        if (number(metadata.get("manualPromptVersion")) < 2) {
            if (manual.contains("【用户专属旅行偏好画像】")) {
                metadata.put("legacyUserCustomizedPrompt", manual);
                metadata.put("manualPromptNeedsReview", true);
                manual = "";
            }
            metadata.put("userCustomizedPrompt", manual);
            metadata.put("manualPromptVersion", 2);
        }
        return manual;
    }

    private String promptLine(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).trim().replaceAll("\\s+", " ");
    }

    private String completePromptText(String value) {
        String normalized = value == null ? "" : java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cf}\\u200B-\\u200D\\uFEFF]", "").replace("\r\n", "\n").trim();
        if (normalized.length() > Math.max(1000, promptMaxChars)) throw new IllegalArgumentException("专属提示词超过长度上限，请精简后保存；原文未被截断。");
        return normalized;
    }

    public Map<String, Object> promptDetails(String userId) {
        String prompt = synthesizePrependPrompt(userId, false);
        var preferences = preferencesService == null ? null : preferencesService.get(userId);
        var metadata = preferences == null ? Map.<String,Object>of() : preferences.legacyMetadata();
        return Map.of("prependPrompt", prompt, "userCustomizedPrompt", metadata.getOrDefault("userCustomizedPrompt", ""),
                "legacyUserCustomizedPrompt", metadata.getOrDefault("legacyUserCustomizedPrompt", ""),
                "manualPromptNeedsReview", Boolean.TRUE.equals(metadata.get("manualPromptNeedsReview")));
    }

    public String promptContext(String userId) {
        if (!validUser(userId)) return "";
        UserPreferences preferences = preferencesService != null ? preferencesService.get(userId) : null;
        if (preferences != null) {
            Object prompt = preferences.legacyMetadata().get("profilePrependPrompt");
            if (prompt instanceof String s && !s.isBlank() && !needsProjectionRefresh(preferences.legacyMetadata())) {
                if (memoryRepository != null && s.length() > Math.max(1000, promptMaxChars) - 100) return boundedPrompt(userId);
                return s + "\n（请在规划行程与推荐餐厅时严格遵循上述个性化偏好约束，非用户明确要求不得违背）";
            }
        }
        String synthesized = synthesizePrependPrompt(userId, false);
        if (memoryRepository != null && synthesized.length() > Math.max(1000, promptMaxChars) - 100) return boundedPrompt(userId);
        return synthesized.isBlank() ? "" : synthesized + "\n（请在规划行程与推荐餐厅时严格遵循上述个性化偏好约束，非用户明确要求不得违背）";
    }

    private boolean needsProjectionRefresh(Map<String, Object> metadata) {
        long synthesizedAt = number(metadata.get("promptLastSynthesizedAt"));
        return number(metadata.get("manualPromptVersion")) < 2 || number(metadata.get("memoryProjectionVersion")) < MemoryPreferenceProjector.PROJECTION_VERSION
                && synthesizedAt > 0 && synthesizedAt >= number(metadata.get("promptLastEditedAt"));
    }

    private boolean isCurrentTripOnly(String text) {
        if (text.matches(".*(后面|以后|后续|一直|通常|习惯|记住|默认|多推荐|帮我推荐|想吃|爱吃|更喜欢).*")) {
            return false;
        }
        return text.matches(".*(仅限今天|只要今天|只在今天|仅本次|仅这一趟|只这趟|单单今天).*");
    }

    /**
     * We accept natural personal statements such as “我不吃香菜” or “后面帮我推荐辣的食物” without
     * forcing the user to say “记住”. A temporary itinerary instruction still
     * never enters the review queue, and every candidate needs confirmation.
     */
    private boolean hasStablePersonalSignal(String text) {
        return text.matches(".*(记住|以后|后续|后面|默认|通常|习惯|总是|一直|经常|一般|更喜欢|偏爱|不喜欢|不吃|不喝|想吃|爱吃|喜辣|多推荐|帮我推荐|多点|少点|过敏|怕晒|怕热|受不了|优先|首选|我|我们|家里|懒|偷懒).*");
    }

    private String normalizeCategory(String value) {
        String normalized = value == null ? "CUSTOM" : value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) { case "PACE", "ACCESSIBILITY", "TRANSPORT", "DIET", "COMPANION", "INTEREST", "BUDGET", "WEATHER", "TIME", "STAY", "CUSTOM" -> normalized; default -> "CUSTOM"; };
    }
    private String clean(String value, int max) {
        if (value == null) return "";
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cf}\\u200B-\\u200D\\uFEFF]", "");
        String compact = normalized.replaceAll("[\\r\\n\\t]+", " ").trim().replaceAll("\\s{2,}", " ");
        return compact.substring(0, Math.min(Math.max(0, max), compact.length()));
    }
    private String completeText(String value, int max, String label) {
        String normalized = clean(value, Integer.MAX_VALUE);
        if (normalized.length() > Math.max(1, max)) throw new IllegalArgumentException(label + "超过长度上限，请精简后保存；原文未被截断。");
        return normalized;
    }
    private boolean validUser(String userId) { return userId != null && !userId.isBlank() && !"anonymous".equalsIgnoreCase(userId); }
    private void requireUser(String userId) { if (!validUser(userId)) throw new IllegalArgumentException("请先登录后管理旅行记忆"); }
    private long number(Object value) { try { return value == null ? 0 : Long.parseLong(String.valueOf(value)); } catch (NumberFormatException ignored) { return 0; } }
    public record Candidate(String category, String content, double confidence) { }
    public record MemoryCandidate(String id, String category, String content, String sourceRef, String status, double confidence, long createdAt, long updatedAt) { }
    private record Observation(String id, String content) { }
}
