package com.ai.guide.domain.memory.service;

import com.ai.guide.domain.memory.model.TravelMemory;
import com.ai.guide.domain.preferences.model.UserPreferencesPatch;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.service.PreferencesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ai.guide.domain.planner.service.IntentLanguagePolicy;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

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
               - 饮食口味/偏好/忌口/过敏（如：“我不吃辣”、“不能吃海鲜”、“特别喜欢地道老火锅与江湖菜”、“对小吃毫无抵抗力”、“吃素”）
               - 体力节奏/步行耐受/无障碍（如：“我膝盖不好不能多爬坡”、“有婴儿车要平路”、“体力有限不要特种兵”、“精力充沛想多逛几个”）
               - 出行方式/作息习惯/天气偏好（如：“出门只打车”、“夜猫子起不来床”、“特别怕晒受不了大太阳”、“下雨天更偏向室内展馆”）
               - 兴趣追求/同行人群（如：“重度摄影爱好者要绝佳机位”、“带爸妈出来玩以舒适为主”、“独爱古建筑和博物馆”）
            2. 临时单次行程操作或普通问答（判定为 false）：
               - 如：“今天先去解放碑”、“把李子坝换掉”、“帮我看一下这个景点的门票”、“明天天气怎么样”、“取消第二个方案”、“导航到洪崖洞”

            输出规则（必须严格仅输出单个合法 JSON 对象，严禁包含任何 Markdown 格式或解释）：
            {
              "hasPreference": true 或 false,
              "category": "DIET | PACE | ACCESSIBILITY | TRANSPORT | BUDGET | WEATHER | TIME | STAY | COMPANION | INTEREST | CUSTOM",
              "content": "20字以内的规范偏好陈述（如：饮食偏好麻辣浓郁与地道火锅 / 偏好少走路，尽量避开台阶与陡坡 / 偏爱历史文化场馆与小众古建）",
              "confidence": 0.75 到 1.0 的浮点数
            }
            若未识别到长期个人偏好，输出：
            {"hasPreference": false}
            """;

    private final JdbcTemplate jdbc;
    private final PreferencesService preferencesService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final MemoryPreferenceProjector preferenceProjector;
    private final TransactionTemplate transactions;
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
        List<TravelMemory> memories = jdbc.query("SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at "
                        + "FROM travel_memory WHERE user_id = ? AND status = 'CONFIRMED' ORDER BY updated_at DESC",
                (rs, row) -> new TravelMemory(rs.getString("id"), rs.getString("category"), rs.getString("content"),
                        rs.getString("source_type"), rs.getString("source_ref"), rs.getString("status"), rs.getDouble("confidence"),
                        rs.getLong("created_at"), rs.getLong("updated_at")), userId);
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
    public TravelMemoryService(JdbcTemplate jdbc) {
        this(jdbc, null, null, null);
    }

    public TravelMemoryService(JdbcTemplate jdbc, PreferencesService preferencesService) {
        this(jdbc, preferencesService, null, null);
    }

    public TravelMemoryService(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc,
                               PreferencesService preferencesService,
                               @Autowired(required = false) ChatClient.Builder chatClientBuilder,
                               @Autowired(required = false) ObjectMapper objectMapper) {
        this(jdbc, preferencesService, chatClientBuilder, objectMapper, new MemoryPreferenceProjector(null));
    }

    @Autowired
    public TravelMemoryService(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbc,
                               PreferencesService preferencesService,
                               @Autowired(required = false) ChatClient.Builder chatClientBuilder,
                               @Autowired(required = false) ObjectMapper objectMapper,
                               MemoryPreferenceProjector preferenceProjector) {
        this.jdbc = jdbc;
        this.transactions = jdbc != null && jdbc.getDataSource() != null
                ? new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())) : null;
        this.preferencesService = preferencesService;
        this.chatClient = chatClientBuilder != null ? chatClientBuilder.build() : null;
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
        if (!validUser(userId) || jdbc == null) return List.of();
        return jdbc.query("SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory WHERE user_id = ? AND status = 'CONFIRMED' ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, row) -> new TravelMemory(rs.getString("id"), rs.getString("category"), rs.getString("content"),
                        rs.getString("source_type"), rs.getString("source_ref"), rs.getString("status"),
                        rs.getDouble("confidence"), rs.getLong("created_at"), rs.getLong("updated_at")), userId, limit, offset)
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
        if (text.matches(".*(过敏|忌口|清真|素食|純素|纯素|乳糖不耐).*")
                || text.matches(".*(不吃|不喝|不想吃|不想喝|不能吃|免葱|忌内脏|不喜欢|不爱|讨厌).*")
                && !text.matches(".*(不是不|并非不|不会不|没有不).*")
                && (text.contains("吃") || text.contains("喝") || matchPositiveDiet(text))) {
            return new Candidate("DIET", text, 0.96);
        }
        // 无障碍与适老化
        if (text.matches(".*(轮椅|无障碍|婴儿车|推车|腿脚不便|行动不便|拄拐|拐杖|术后|台阶高).*")) {
            return new Candidate("ACCESSIBILITY", "出行需要无障碍、少台阶与便于休息的安排", 0.95);
        }

        // 饮食偏好：过敏、忌口、禁忌（最高优门禁，DEF-R3-03）
        if (text.matches(".*(过敏|忌口|清真|素食|纯素|不能吃花生|海鲜过敏|不吃内脏|忌内脏|乳糖不耐).*")) {
            return new Candidate("DIET", text, 0.96);
        }

        // 饮食偏好：双重否定吃辣（如：“不是不吃辣”、“并非不吃辣”，识别为吃辣偏好）
        if (text.matches(".*(不是不|并非不|不会不|没有不)[^，。！？\n]*辣.*")) {
            return new Candidate("DIET", "饮食偏好麻辣、地道重庆火锅与江湖菜风味", 0.94);
        }

        // 饮食偏好：清淡、少辣、不吃辣（否定优先门禁，DEF-R3-03）
        if (text.matches(".*(不吃辣|不能吃辣|少辣|清淡|不辣|免辣|怕辣|不能碰辣|不吃香菜|不吃.*海鲜|不吃肉|少油|少盐|免葱|养生餐).*")) {
            if (text.matches(".*(火锅|串串|汤锅).*")) {
                return new Candidate("DIET", "饮食偏好清淡少辣（火锅偏好清汤或番茄锅，避开辛辣刺激）", 0.94);
            }
            return new Candidate("DIET", "饮食偏好清淡少辣，避开刺激性食物与忌口", 0.92);
        }

        // A rejection or mixed preference keeps every source clause; positive food
        // words elsewhere must not override the rejected object.
        if (IntentLanguagePolicy.clauses(text).stream().anyMatch(clause -> IntentLanguagePolicy.positiveText(clause).isBlank())
                && !text.matches(".*(不是不|并非不|不会不|没有不).*")) {
            return new Candidate(text.contains("吃") || text.contains("喝") || matchPositiveDiet(text)
                    ? "DIET" : "CUSTOM", text, 0.92);
        }

        // 饮食偏好：火锅、重辣、江湖菜（排除否定语义后的正向偏好）
        if (text.matches(".*(火锅|老火锅|九宫格|串串|冷锅串串|美蛙鱼头|麻辣|喜辣|爱吃辣|能吃辣|喜欢.*辣|多点辣|偏好.*辣|推荐.*辣|辣的食物|重口味|重辣|微辣|无辣不欢|嗜辣|江湖菜|毛血旺|辣子鸡|水煮鱼|川菜|烧烤).*")) {
            return new Candidate("DIET", "饮食偏好麻辣、地道重庆火锅与江湖菜风味", 0.94);
        }

        // 饮食/休闲偏好：老茶馆、盖碗茶、茶楼、品茗慢生活
        if (text.matches(".*(茶馆|老茶馆|盖碗茶|喝茶|品茶|茶楼|茶舍|茶道|工夫茶).*")) {
            return new Candidate("DIET", "偏好特色老茶馆、盖碗茶与慢生活品茗体验", 0.94);
        }

        // 饮食/休闲偏好：景观咖啡、下午茶、特调
        if (text.matches(".*(咖啡|下午茶|拿铁|特调|烘焙|精酿).*")) {
            return new Candidate("DIET", "偏好景观咖啡馆、独立特调与下午茶小憩", 0.93);
        }

        // 饮食偏好：甜食甜点、糖水、冰粉
        if (text.matches(".*(甜的|甜品|甜食|吃甜|想吃甜|糖水|冰粉|凉糕).*")) {
            return new Candidate("DIET", "偏好甜品糖水与特色甜点", 0.92);
        }

        // 饮食偏好：街头名点、小吃、夜市
        if (text.matches(".*(小吃|酸辣粉|小面|重庆小面|抄手|老麻抄手|豆花|陈麻花|糍粑|夜市|路边摊).*")) {
            return new Candidate("DIET", "偏好街头特色小吃、巴渝名点与市井烟火风味", 0.92);
        }

        // 体力节奏：少走路、平路、慢节奏
        if (text.matches(".*(少走|少走路|不想走|走不动|爬不动|懒得走|有点懒|很懒|太懒|偷懒|犯懒|不想动|少爬坡|少台阶|轻松一点|不爱走路|体力.*有限|慢节奏|休闲|不想太累|别太赶|不要特种兵|不特种兵|平地|平路|省力|省腿).*")) {
            return new Candidate("PACE", "偏好少走路，优先平路、电梯与短步行", 0.93);
        }

        // 体力节奏：特种兵、高密度、深度游
        if (text.matches(".*(特种兵|多走走|充实|暴走|多看几个|多安排点|多逛几个|深度游|不怕累|精力充沛|徒步).*")) {
            return new Candidate("PACE", "偏好高密度充实游览，体力充沛，愿意多走多看", 0.89);
        }

        // 交通偏好：打车、网约车
        if (text.matches(".*(打车|出租车|网约车|滴滴|包车|自驾|租车|晕车|不想挤).*")) {
            return new Candidate("TRANSPORT", "出行时优先打车或网约车", 0.91);
        }

        // 交通偏好：地铁、公交、轻轨
        if (text.matches(".*(地铁|公交|公共交通|轻轨|轨道交通|体验轻轨|低碳出行).*")) {
            return new Candidate("TRANSPORT", "出行时更愿意使用公共交通", 0.86);
        }

        // 预算：经济型、性价比、穷游
        if (text.matches(".*(预算有限|省钱|性价比|免费|穷游|学生党|平价|实惠).*")) {
            return new Candidate("BUDGET", "出行时重视预算与性价比，优先平价与免费景点", 0.85);
        }

        // 预算：高端品质
        if (text.matches(".*(品质|高端|讲究品质|黑珍珠|预算充足|不差钱|高档).*")) {
            return new Candidate("BUDGET", "注重出行品质与舒适度，偏好高端体验", 0.88);
        }

        // 天气体感：怕晒、避雨、室内
        if (text.matches(".*(怕晒|怕热|容易中暑|雨天|下雨.*室内|避雨|避暑|防晒|吹空调).*")) {
            return new Candidate("WEATHER", "行程更在意天气体感，优先阴凉、室内或可避雨安排", 0.87);
        }

        // 作息时间：晚起、从容、夜猫子
        if (text.matches(".*(不喜欢早起|睡到自然醒|晚起|起不来|夜猫子|上午不出发|下午才出门).*")) {
            return new Candidate("TIME", "出行节奏不宜太早，偏好从容晚起安排", 0.84);
        }

        // 住宿倾向：安静、江景、近地铁
        if (text.matches(".*(酒店.*安静|住宿.*安静|住得.*方便|靠近地铁.*住|住江景).*")) {
            return new Candidate("STAY", "住宿更看重安静、江景与交通便利", 0.82);
        }

        // 同行人照顾：亲子、儿童
        if (text.matches(".*(带娃|孩子|宝宝|小朋友|亲子|游乐场|童趣|推车出行).*")) {
            return new Candidate("COMPANION", "出行时常需要兼顾亲子与儿童体验", 0.84);
        }

        // 同行人照顾：父母、长辈、老年人
        if (text.matches(".*(父母|长辈|老人|爸妈|带老人|老年人).*")) {
            return new Candidate("COMPANION", "出行时常需要兼顾长辈的体力与舒适度", 0.84);
        }

        // 同行人照顾：情侣、蜜月、朋友
        if (text.matches(".*(情侣|对象|爱人|两个人|闺蜜|朋友|蜜月).*")) {
            return new Candidate("COMPANION", "通常以情侣或朋友结伴方式安排行程", 0.84);
        }

        // 兴趣体验：拍照、摄影、绝佳机位
        if (text.matches(".*(拍照|摄影|出片|机位|找角度|打卡地|美照|好看|旅拍|汉服).*")) {
            return new Candidate("INTEREST", "旅行时重视摄影出片与绝佳机位视角", 0.85);
        }

        // 兴趣体验：山城夜景、魔幻8D、两江漫步
        if (text.matches(".*(夜景|江景|两江|赛博朋克|魔幻|立交桥|索道|轻轨穿楼|citywalk|城市漫步|天台).*")) {
            return new Candidate("INTEREST", "喜欢山城夜景、魔幻8D与滨江漫步体验", 0.85);
        }

        // 兴趣体验：人文历史、文创老街、展览古迹
        if (text.matches(".*(博物馆|美术馆|展览|人文|历史|古迹|老建筑|非遗|红色|文化|古镇|老街|文创|书店).*")) {
            return new Candidate("INTEREST", "偏爱人文历史与室内文化场馆", 0.87);
        }

        // 兴趣体验：自然山水、户外风光
        if (text.matches(".*(自然|徒步|爬山|山水|公园|江岸|森林|峡谷|天坑|地质|吸氧).*")) {
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
        if (chatClient == null) return null;
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() ->
                    chatClient.prompt()
                            .messages(new SystemMessage(LLM_MEMORY_SYSTEM_PROMPT), new UserMessage(text))
                            .call()
                            .content()
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
        String normalized = content.replace("不是不吃辣", "吃辣").replace("并非不吃辣", "吃辣")
                .replace("不会不吃辣", "吃辣").replace("没有不吃辣", "吃辣");
        if (normalized.matches(".*(清淡|少辣|不吃辣|不能吃辣|免辣|不辣|怕辣).*")) return -1;
        String positive = IntentLanguagePolicy.positiveText(normalized);
        return positive.matches(".*(麻辣|喜辣|爱吃辣|辣味|吃辣|重辣|嗜辣|无辣不欢).*") ? 1 : 0;
    }

    private void handleConflictingMemories(String userId, String newContent, String excludedId) {
        if (!validUser(userId) || newContent == null || jdbc == null) return;
        for (Map<String,Object> row : jdbc.queryForList(
                "SELECT id, content FROM travel_memory WHERE user_id = ? AND status = 'CONFIRMED'", userId)) {
            String id = String.valueOf(row.get("id"));
            if (id.equals(excludedId)) continue;
            String old = String.valueOf(row.get("content"));
            int before = spicePolarity(old), after = spicePolarity(newContent);
            boolean conflict = before != 0 && after != 0 && before != after;
            String oldPositive = IntentLanguagePolicy.positiveText(old);
            String newPositive = IntentLanguagePolicy.positiveText(newContent);
            conflict |= newContent.matches(".*(少走路|少爬坡|平路|避台阶).*")
                    && oldPositive.matches(".*(特种兵|暴走|多走走).*");
            conflict |= newPositive.matches(".*(特种兵|暴走|多走走).*")
                    && old.matches(".*(少走路|少爬坡|平路|避台阶).*");
            if (conflict) {
                var kept = new ArrayList<String>();
                for (String clause : old.split("[，,；;。\\n、]+")) {
                    boolean conflicting = spicePolarity(clause) != 0 && after != 0 && spicePolarity(clause) != after;
                    conflicting |= newContent.matches(".*(少走路|少爬坡|平路|避台阶).*") && IntentLanguagePolicy.positiveText(clause).matches(".*(特种兵|暴走|多走走).*");
                    conflicting |= newPositive.matches(".*(特种兵|暴走|多走走).*") && clause.matches(".*(少走路|少爬坡|平路|避台阶).*");
                    if (!conflicting) { if (!clause.isBlank()) kept.add(clause.trim()); continue; }
                    // Only retire a standalone preference clause. Unknown residual meaning must survive.
                    String residual = clause.replaceAll("不能吃辣|不是不吃辣|并非不吃辣|不会不吃辣|没有不吃辣|不吃辣|爱吃辣|无辣不欢|麻辣|喜辣|辣味|吃辣|重辣|嗜辣|清淡|少辣|免辣|不辣|怕辣|少走路|少爬坡|平路|避台阶|特种兵|暴走|多走走", "");
                    residual = preferenceProjector.removePresentationWords(residual).replaceAll("[\\s\\p{P}]+", "");
                    if (!residual.isBlank()) throw new IllegalArgumentException("旧记忆包含相互冲突的偏好和其他信息，请先编辑这条记忆，保留过敏、同行人与其他限制：" + old);
                }
                if (kept.isEmpty()) jdbc.update("UPDATE travel_memory SET status = 'DELETED', updated_at = ? WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'", System.currentTimeMillis(), id, userId);
                else jdbc.update("UPDATE travel_memory SET content = ?, updated_at = ? WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'", String.join("，", kept), System.currentTimeMillis(), id, userId);
            }
        }
    }

    private <T> T atomic(Supplier<T> action) {
        return transactions == null ? action.get() : transactions.execute(status -> action.get());
    }

    public TravelMemory confirm(String userId, String content, String category, String sourceType) {
        return confirm(userId, content, category, sourceType, "");
    }

    public TravelMemory confirm(String userId, String content, String category, String sourceType, String sourceRef) {
        return atomic(() -> confirmAtomic(userId, content, category, sourceType, sourceRef));
    }

    private TravelMemory confirmAtomic(String userId, String content, String category, String sourceType, String sourceRef) {
        requireUser(userId);
        String safeContent = completeText(content, contentMaxChars, "记忆内容");
        if (safeContent.isBlank()) throw new IllegalArgumentException("记忆内容不能为空");
        String safeCategory = normalizeCategory(category);
        long now = System.currentTimeMillis();
        handleConflictingMemories(userId, safeContent, null);
        List<TravelMemory> same = jdbc.query("SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at " +
                        "FROM travel_memory WHERE user_id = ? AND category = ? AND content = ? AND status = 'CONFIRMED' LIMIT 1",
                (rs, row) -> new TravelMemory(rs.getString("id"), rs.getString("category"), rs.getString("content"),
                        rs.getString("source_type"), rs.getString("source_ref"), rs.getString("status"), rs.getDouble("confidence"),
                        rs.getLong("created_at"), rs.getLong("updated_at")), userId, safeCategory, safeContent);
        if (!same.isEmpty()) {
            TravelMemory existing = same.get(0);
            jdbc.update("UPDATE travel_memory SET updated_at = ? WHERE id = ? AND user_id = ?", now, existing.id(), userId);
            synthesizePrependPrompt(userId, false);
            return preferenceProjector.project(new TravelMemory(existing.id(), existing.category(), existing.content(), existing.sourceType(), existing.sourceRef(), existing.status(), existing.confidence(), existing.createdAt(), now));
        }
        String id = "mem-" + UUID.randomUUID();
        String source = "USER_EDIT".equalsIgnoreCase(sourceType) ? "USER_EDIT" : "CHAT_CONFIRMED";
        String safeSourceRef = sourceRef == null ? "" : clean(sourceRef, 120);
        jdbc.update("INSERT INTO travel_memory (id, user_id, category, content, source_type, source_ref, status, confidence, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)",
                id, userId, safeCategory, safeContent, source, safeSourceRef, source.equals("USER_EDIT") ? 1.0 : 0.9, now, now);
        synthesizePrependPrompt(userId, false);
        return preferenceProjector.project(new TravelMemory(id, safeCategory, safeContent, source, safeSourceRef, "CONFIRMED", source.equals("USER_EDIT") ? 1.0 : 0.9, now, now));
    }

    public TravelMemory update(String userId, String id, String content, String category) {
        return atomic(() -> updateAtomic(userId, id, content, category));
    }

    private TravelMemory updateAtomic(String userId, String id, String content, String category) {
        requireUser(userId);
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM travel_memory WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'", Integer.class, id, userId);
        if (existing == null || existing == 0) throw new IllegalArgumentException("旅行记忆不存在或无权修改");
        String safeContent = completeText(content, contentMaxChars, "记忆内容");
        if (safeContent.isBlank()) throw new IllegalArgumentException("记忆内容不能为空");
        handleConflictingMemories(userId, safeContent, id);
        int changed = jdbc.update("UPDATE travel_memory SET content = ?, category = ?, source_type = 'USER_EDIT', confidence = 1.0, updated_at = ? WHERE id = ? AND user_id = ? AND status = 'CONFIRMED'",
                safeContent, normalizeCategory(category), System.currentTimeMillis(), id, userId);
        if (changed == 0) throw new IllegalArgumentException("旅行记忆不存在或无权修改");
        synthesizePrependPrompt(userId, false);
        return list(userId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    public void delete(String userId, String id) {
        atomic(() -> { deleteAtomic(userId, id); return null; });
    }

    private void deleteAtomic(String userId, String id) {
        requireUser(userId);
        jdbc.update("UPDATE travel_memory SET status = 'DELETED', updated_at = ? WHERE id = ? AND user_id = ?", System.currentTimeMillis(), id, userId);
        synthesizePrependPrompt(userId, false);
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
        return atomic(() -> confirmCandidateAtomic(userId, candidateId));
    }

    private TravelMemory confirmCandidateAtomic(String userId, String candidateId) {
        requireUser(userId);
        if (jdbc == null) throw new IllegalArgumentException("记忆服务暂不可用");
        List<MemoryCandidate> rows = jdbc.query("SELECT id, category, content, source_ref, status, confidence, created_at, updated_at FROM travel_memory_candidate WHERE id = ? AND user_id = ? AND status = 'PENDING'",
                (rs, row) -> new MemoryCandidate(rs.getString("id"), rs.getString("category"), rs.getString("content"), rs.getString("source_ref"), rs.getString("status"), rs.getDouble("confidence"), rs.getLong("created_at"), rs.getLong("updated_at")), candidateId, userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("记忆候选不存在或已处理");
        MemoryCandidate candidate = rows.get(0);
        int claimed = jdbc.update("UPDATE travel_memory_candidate SET status = 'CONFIRMED', updated_at = ? WHERE id = ? AND user_id = ? AND status = 'PENDING'", System.currentTimeMillis(), candidateId, userId);
        if (claimed != 1) throw new IllegalArgumentException("记忆候选不存在或已处理");
        TravelMemory memory = confirm(userId, candidate.content(), candidate.category(), "CHAT_CONFIRMED", candidate.sourceRef());
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
        if (!Boolean.TRUE.equals(preferencesService.get(userId).legacyMetadata().get("travelMemoryEnabled"))) return;
        List<Observation> observations = jdbc.query("SELECT id, content FROM travel_memory_observation WHERE user_id = ? AND status = 'PENDING' ORDER BY created_at ASC LIMIT 60",
                (rs, row) -> new Observation(rs.getString("id"), rs.getString("content")), userId);
        Map<String,Candidate> detected = new LinkedHashMap<>();
        for (Observation observation : observations) detected.put(observation.id(), suggest(observation.content()));
        // Extraction may call a model. Only DB writes and the projection CAS belong in the transaction.
        atomic(() -> {
            UserPreferences preferences = preferencesService.get(userId);
            Map<String,Object> metadata = new LinkedHashMap<>(preferences.legacyMetadata());
            if (!Boolean.TRUE.equals(metadata.get("travelMemoryEnabled"))) return null;
            long now = System.currentTimeMillis();
            for (Observation observation : observations) {
                int claimed = jdbc.update("UPDATE travel_memory_observation SET status = 'REVIEWED' WHERE id = ? AND user_id = ? AND status = 'PENDING'", observation.id(), userId);
                if (claimed != 1) continue;
                Candidate candidate = detected.get(observation.id());
                if (candidate == null) continue;
                Integer duplicate = jdbc.queryForObject("SELECT COUNT(*) FROM travel_memory_candidate WHERE user_id = ? AND category = ? AND content = ? AND status = 'PENDING'", Integer.class, userId, candidate.category(), candidate.content());
                if (duplicate == null || duplicate == 0) jdbc.update("INSERT INTO travel_memory_candidate (id, user_id, category, content, source_ref, status, confidence, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)",
                        "mem-candidate-" + UUID.randomUUID(), userId, candidate.category(), candidate.content(), clean(observation.content(), 120), candidate.confidence(), now, now);
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
        if (!validUser(userId) || jdbc == null) return "";
        UserPreferences preferences = preferencesService != null ? preferencesService.get(userId) : null;
        Map<String, Object> metadata = preferences != null ? new LinkedHashMap<>(preferences.legacyMetadata()) : new LinkedHashMap<>();

        String userCustomized = manualPrompt(metadata);

        List<TravelMemory> items = jdbc.query(
                "SELECT id, category, content, source_type, source_ref, status, confidence, created_at, updated_at " +
                "FROM travel_memory WHERE user_id = ? AND status = 'CONFIRMED' ORDER BY updated_at ASC",
                (rs, row) -> new TravelMemory(rs.getString("id"), rs.getString("category"), rs.getString("content"),
                        rs.getString("source_type"), rs.getString("source_ref"), rs.getString("status"),
                        rs.getDouble("confidence"), rs.getLong("created_at"), rs.getLong("updated_at")), userId);

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
                if (jdbc != null && s.length() > Math.max(1000, promptMaxChars) - 100) return boundedPrompt(userId);
                return s + "\n（请在规划行程与推荐餐厅时严格遵循上述个性化偏好约束，非用户明确要求不得违背）";
            }
        }
        String synthesized = synthesizePrependPrompt(userId, false);
        if (jdbc != null && synthesized.length() > Math.max(1000, promptMaxChars) - 100) return boundedPrompt(userId);
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
