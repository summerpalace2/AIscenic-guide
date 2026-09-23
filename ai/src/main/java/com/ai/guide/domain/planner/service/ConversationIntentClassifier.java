package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.PlanPageContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionAliasMatcher;
import com.ai.guide.domain.attraction.service.AttractionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话式行程调整规则与模式意图分类器
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 * 架构职责：基于正则与模式匹配快速识别换景点、增删站点、雨天重排、减小密度等核心意图，作为大模型的快速确定性兜底。
 */
@Service
public class ConversationIntentClassifier {

    private final AttractionService attractionService;
    private final AttractionAliasMatcher aliasMatcher;

    public ConversationIntentClassifier() {
        this(null, null);
    }

    @Autowired(required = false)
    public ConversationIntentClassifier(AttractionService attractionService) {
        this(attractionService, new AttractionAliasMatcher(attractionService));
    }

    public ConversationIntentClassifier(AttractionService attractionService, AttractionAliasMatcher aliasMatcher) {
        this.attractionService = attractionService;
        this.aliasMatcher = aliasMatcher != null ? aliasMatcher : new AttractionAliasMatcher(attractionService);
    }

    private static final Pattern COUNT_PATTERN = Pattern.compile("少(?:推荐|安排|去|逛|看)?\\s*([一二两123456789\\d]+)\\s*个");
    private static final Pattern CANDIDATE_INDEX_PATTERN = Pattern.compile("(?:换成|选|应用)第?\\s*([一二两三四五12345])\\s*个?(?:方案|候选)?");
    private static final Pattern REPLACEMENT_CONNECTOR_PATTERN = Pattern.compile("替换成|替换为|换成|换为|改成|改为|替代");
    private static final Pattern GENERIC_DAY_PATTERN = Pattern.compile("(?:第\\s*([一二三四五六七八九十\\d]+)\\s*天|day\\s*(\\d+))", Pattern.CASE_INSENSITIVE);


    public PlanAdjustmentIntent classify(String message, PlanPageContext context) {
        String text = message == null ? "" : message.trim();
        PlanPageContext effectiveContext = context == null ? new PlanPageContext() : context;
        Integer activeDay = effectiveContext.getActiveDay() == null ? 1 : effectiveContext.getActiveDay();
        String selectedStop = effectiveContext.getSelectedStopId();
        String activeProposal = effectiveContext.getActiveProposalId();
        List<String> pinned = effectiveContext.getPinnedStopIds() == null ? List.of() : effectiveContext.getPinnedStopIds();

        if (text.isBlank()) {
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.UNKNOWN)
                    .operation("UNKNOWN")
                    .rawMessage(text)
                    .clarificationQuestion("请输入您的调整要求或对景点的提问。")
                    .requiresClarification(true)
                    .build();
        }

        // 提取偏好与环境条件
        List<String> preferences = extractPreferences(text);
        List<String> conditions = extractConditions(text);
        Integer explicitDay = extractExplicitDayNumber(text);
        Integer targetDay = extractDayNumber(text, activeDay);
        String matchedVenueId = extractMentionedVenueId(text);
        boolean mentionsSelectedStop = text.matches(".*(这个景点|当前景点|这个地方|这里|这个|当前站|此站).*");
        String bracketedTarget = null;
        Matcher bm = Pattern.compile("[【\\[](.+?)[】\\]]").matcher(text);
        if (bm.find()) {
            bracketedTarget = bm.group(1).trim();
        }
        String targetStop = mentionsSelectedStop && selectedStop != null && !selectedStop.isBlank()
                ? selectedStop : (selectedStop != null && !selectedStop.isBlank() && !hasOtherVenue(text, selectedStop) ? selectedStop : null);
        if (targetStop == null && selectedStop != null && !selectedStop.isBlank() && bracketedTarget != null) {
            targetStop = selectedStop;
        }

        // 明确的“源景点 -> 目标景点”替换必须优先于泛化的候选推荐。
        // 例如“把三峡博物馆替换成大剧院”“把最后一个晚间行程改成大剧院”。
        Matcher replacementMatcher = REPLACEMENT_CONNECTOR_PATTERN.matcher(text);
        if (replacementMatcher.find()) {
            String sourceText = text.substring(0, replacementMatcher.start());
            String replacementText = text.substring(replacementMatcher.end());
            String sourceVenueId = extractMentionedVenueId(sourceText);
            String sourceVenueAlias = extractMentionedVenueAlias(sourceText);
            String replacementVenueId = extractMentionedVenueId(replacementText);
            String replacementVenueName = cleanVenueText(replacementText);
            boolean relativeSource = sourceText.matches(".*(最后一个|最后一站|末尾|收尾|晚间最后|晚上最后).*");
            if (replacementVenueId != null && (sourceVenueId != null || relativeSource || targetStop != null)) {
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.SUGGEST_REPLACEMENTS)
                        .operation("REPLACE_STOP")
                        .scope("STOP")
                        .dayNumber(targetDay)
                        .targetStopId(targetStop)
                        .targetStopReference(mentionsSelectedStop ? "当前景点" : (sourceVenueAlias != null ? sourceVenueAlias : (relativeSource ? sourceText.trim() : null)))
                        .targetAttractionId(replacementVenueId)
                        .replacementPlaceName(replacementVenueName)
                        .replacementPlaceId(replacementVenueId)
                        .preferences(preferences)
                        .conditions(conditions)
                        .pinnedStopIds(new ArrayList<>(pinned))
                        .rawMessage(text)
                        .confidence(0.98)
                        .build();
            }
        }

        // A normal chat answer can contain a narrative plan such as “方案A：文化室内线” or “方案A：南山本地辣味收尾，泉水鸡正餐”.
        // When the user then says “选这个帮我改行程”, there is no server-side Proposal yet.
        boolean selectsNarrativePlan = (text.matches(".*(?:方案\\s*[A-Za-zＡ-Ｚ]|选(?:择)?(?:这个|它|这套|这条)|就(?:选|用)这个|按这个(?:方案)?|采用这个(?:方案)?).*")
                && text.matches(".*(?:改|调整|修改|应用|重排|重新规划|行程|安排).*"))
                || text.matches(".*(?:在当前行程草稿中|在行程中|在当前行程中)?(?:补充|新增|添加|加入|安排|串联|安排上|排入|纳入).*(?:段|线|片区|行程|观展|打卡).*")
                || text.matches(".*在当前行程草稿中(?:补充|新增|添加|加入|调整|安排|放入).*");
        if (selectsNarrativePlan) {
            boolean hasAttractionsInPlan = text.matches(".*(?:补充|新增|添加|加入|安排).*")
                    || text.matches(".*(?:三峡博物馆|陈列馆|美术馆|博物馆|科技馆|动物园|大礼堂).*");
            boolean selectsDiningPlan = !hasAttractionsInPlan && text.matches(".*(美食|小吃|火锅|江湖菜|泉水鸡|辣子鸡|烤鱼|正餐|晚餐|午餐|早餐|早点|早饭|小面|面馆|面条|抄手|豆花|包子|油条|茶歇|饮品|餐厅|特色菜|辣味).*");
            if (selectsDiningPlan) {
                String flavor = "特色美食";
                Matcher fm = Pattern.compile("([\\u4e00-\\u9fa5]{2,6}?(?:火锅|老火锅|江湖菜|川菜|泉水鸡|辣子鸡|毛血旺|酸菜鱼|烤鱼|小吃|抄手|小面|串串|烧烤|茶歇|汤锅|早点|早餐))").matcher(text);
                if (fm.find()) {
                    flavor = fm.group(1);
                    flavor = flavor.replaceAll(".*(?:换成|改成|换为|换|选|为|吃|去吃|采用|方案[A-Za-z0-9]?)", "");
                    if (flavor.isBlank()) {
                        flavor = text.matches(".*(早).*") ? "早餐小面" : "重庆小面";
                    }
                } else if (text.matches(".*(小面|面馆|面条).*")) {
                    flavor = text.matches(".*(早).*") ? "早餐小面" : "重庆小面";
                } else if (text.matches(".*(早).*")) {
                    flavor = "特色早餐";
                } else if (text.matches(".*(辣|江湖).*")) {
                    flavor = "特色江湖菜";
                } else if (text.matches(".*(火锅|串串).*")) {
                    flavor = "重庆火锅";
                }
                List<String> diningPrefs = new ArrayList<>(preferences);
                addIfMissing(diningPrefs, true, "FOOD");
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.SUGGEST_REPLACEMENTS)
                        .operation("REPLACE_STOP")
                        .scope("STOP")
                        .dayNumber(targetDay)
                        .targetStopId(targetStop)
                        .replacementPlaceName(flavor)
                        .preferences(diningPrefs)
                        .conditions(new ArrayList<>(conditions))
                        .pinnedStopIds(new ArrayList<>(pinned))
                        .rawMessage(text)
                        .confidence(0.96)
                        .build();
            }
            List<String> narrativePreferences = new ArrayList<>(preferences);
            addIfMissing(narrativePreferences, text.matches(".*(?:室内|文化室内|美术馆|博物馆|室内线).*"), "INDOOR");
            addIfMissing(narrativePreferences, text.matches(".*(?:少走路|文化室内|室内线|平街|可久坐|久坐).*"), "LOW_WALKING");
            addIfMissing(narrativePreferences, text.matches(".*(?:人文|文化|历史|博物馆|美术馆|陈列馆).*"), "HISTORY");
            addIfMissing(narrativePreferences, text.matches(".*(?:美食|小吃|午餐|晚餐|面馆|抄手).*"), "FOOD");
            addIfMissing(narrativePreferences, text.matches(".*(?:同片区|同区|顺路).*"), "SAME_DISTRICT");
            if (narrativePreferences.isEmpty()) {
                narrativePreferences.add("HISTORY");
            }
            boolean isLocalAdjustment = text.matches(".*(局部|这天|今天|当天|第[一二三四1-9]天).*")
                    || (explicitDay != null && explicitDay > 0)
                    || text.matches(".*(?:补充|新增|添加|加入|安排).*段.*");
            String narrativeScope = isLocalAdjustment ? "DAY" : "TRIP";
            int effectiveTargetDay = targetDay != null ? targetDay : (activeDay > 0 ? activeDay : 1);
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.REPLAN_DAY)
                    .operation("REPLAN_DAY")
                    .scope(narrativeScope)
                    .dayNumber(effectiveTargetDay)
                    .targetAttractionId(matchedVenueId)
                    .preferences(narrativePreferences)
                    .conditions(new ArrayList<>(conditions))
                    .pinnedStopIds(new ArrayList<>(pinned))
                    .rawMessage(text)
                    .confidence(0.95)
                    .build();
        }

        // 用户可以沿着当前选中的站点继续提出“推荐同片区其他室内景点”这类候选请求。
        // 这不是普通问答，也不要求用户重复输入原景点；选中站点或上一份提案会提供替换源。
        boolean asksForAlternativeCandidates = text.matches(".*(?:推荐|提供|找|看看|换|替换).*(?:同片区|附近|其他|别的|更多|不同|室内|低步行|少走路|小众|平替).*(?:景点|地点|室内|场馆|候选|备选|方案|平替).*")
                || text.matches(".*(?:同片区|附近|其他|别的|更多|不同|室内|低步行|少走路|小众|平替).*(?:景点|地点|室内|场馆|平替).*(?:推荐|换|找|提供).*")
                || (bracketedTarget != null && text.matches(".*(?:推荐|换|替换|备选|候选).*"));
        if (asksForAlternativeCandidates) {
            // 快捷入口会把当前卡片名称写进文案，例如“请推荐【南温泉风景区】的
            // 同片区可替换景点”。这仍是在询问该卡片的替换候选，不能把名称误判
            // 为另一个目标而丢弃页面已选站点。
            if (selectedStop != null && !selectedStop.isBlank()) {
                targetStop = selectedStop;
            }

            if ((targetStop == null || targetStop.isBlank()) && bracketedTarget == null && matchedVenueId == null) {
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.CLARIFICATION)
                        .operation("REPLACE_STOP")
                        .scope("STOP")
                        .dayNumber(targetDay)
                        .missingFields(List.of("TARGET_STOP"))
                        .clarificationQuestion("请先选中想要替换的景点，我就能为你推荐同片区或室内候选。")
                        .requiresClarification(true)
                        .rawMessage(text)
                        .confidence(0.9)
                        .build();
            }
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.SUGGEST_REPLACEMENTS)
                    .operation("REPLACE_STOP")
                    .scope("STOP")
                    .dayNumber(targetDay)
                    .targetStopId(targetStop)
                    .targetStopReference(bracketedTarget != null ? bracketedTarget : (mentionsSelectedStop ? "当前景点" : extractMentionedVenueAlias(text)))
                    .preferences(preferences)
                    .conditions(conditions)
                    .pinnedStopIds(new ArrayList<>(pinned))
                    .rawMessage(text)
                    .confidence(0.94)
                    .build();
        }

        // 1. 换成第 N 个候选 / 指定选项 (APPLY_REPLACEMENT)
        // 仅在已有活跃调整方案 (activeProposal != null) 时，序号输入才作为方案选项应用；
        // 若没有活跃方案，则“2”、“选2”、“第二个”属于对上一轮意图澄清问题的选择，不能误判为方案应用。
        boolean hasActiveProposal = activeProposal != null && !activeProposal.isBlank();
        Matcher candidateMatcher = CANDIDATE_INDEX_PATTERN.matcher(text);
        if (hasActiveProposal && (candidateMatcher.find() || text.matches(".*(换成第[12345一二两三四五]个|选第[12345一二两三四五]个|方案[12345一二两三四五]|确认替换|应用方案).*"))) {
            int index = extractCandidateIndex(text);
            if (index > 3) {
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.CLARIFICATION)
                        .operation("CLARIFICATION")
                        .rawMessage(text)
                        .clarificationQuestion("当前调整候选方案提供第 1 到 3 个选项，请选择有效的候选序号（如“选方案一”或“换成第二个”）。")
                        .requiresClarification(true)
                        .build();
            }
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.APPLY_REPLACEMENT)
                    .operation("APPLY_REPLACEMENT")
                    .scope("STOP")
                    .candidateIndex(index)
                    .optionId("option-" + index)
                    .proposalId(activeProposal)
                    .rawMessage(text)
                    .confidence(0.95)
                    .build();
        }

        // 澄清选项处理（在无 activeProposal 时响应多轮选择）
        if (!hasActiveProposal) {
            boolean isOption2 = text.matches("^(?:选|选择|第)?\\s*[2二两]\\s*(?:个|项)?(?:[、.：:]?\\s*(?:替换|换).*|方案)?$")
                    || text.matches(".*(?:替换|换)(?:具体)?景点.*");
            boolean isOption1 = text.matches("^(?:选|选择|第)?\\s*1\\s*(?:个|项)?(?:[、.：:]?\\s*(?:重新规划|重排).*|方案)?$")
                    || text.matches(".*重新规划.*");
            boolean isOption3 = text.matches("^(?:选|选择|第)?\\s*3\\s*(?:个|项)?(?:[、.：:]?\\s*(?:减少|精简).*|方案)?$")
                    || text.matches(".*(?:减少|精简).*景点.*");
            boolean isOption4 = text.matches("^(?:选|选择|第)?\\s*4\\s*(?:个|项)?(?:[、.：:]?\\s*(?:添加|新增).*|方案)?$")
                    || text.matches(".*(?:添加|新增).*景点.*");

            if (isOption2) {
                int day = targetDay != null ? targetDay : activeDay;
                if (targetStop != null || matchedVenueId != null) {
                    return PlanAdjustmentIntent.builder()
                            .type(ConversationIntentType.SUGGEST_REPLACEMENTS)
                            .operation("REPLACE_STOP")
                            .scope("STOP")
                            .dayNumber(day)
                            .targetStopId(targetStop)
                            .targetStopReference(mentionsSelectedStop ? "当前景点" : extractMentionedVenueAlias(text))
                            .preferences(preferences)
                            .conditions(conditions)
                            .pinnedStopIds(new ArrayList<>(pinned))
                            .rawMessage(text)
                            .confidence(0.92)
                            .build();
                }
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.CLARIFICATION)
                        .operation("REPLACE_STOP")
                        .scope("STOP")
                        .dayNumber(day)
                        .targetDayReference("第" + day + "天")
                        .clarificationQuestion("你想替换第 " + day + " 天的哪一个具体景点？你可以直接回复景点名称（如“替换红岩革命纪念馆”或“换掉磁器口”），或在左侧行程卡片中点击【选中此站】。")
                        .requiresClarification(true)
                        .rawMessage(text)
                        .confidence(0.92)
                        .build();
            }
            if (isOption1) {
                int day = targetDay != null ? targetDay : activeDay;
                if (!preferences.isEmpty() || !conditions.isEmpty()) {
                    return PlanAdjustmentIntent.builder()
                            .type(ConversationIntentType.REPLAN_DAY)
                            .operation("REPLAN_DAY")
                            .scope("DAY")
                            .dayNumber(day)
                            .targetDayReference("第" + day + "天")
                            .preferences(preferences)
                            .conditions(conditions)
                            .pinnedStopIds(new ArrayList<>(pinned))
                            .rawMessage(text)
                            .confidence(0.92)
                            .build();
                }
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.CLARIFICATION)
                        .operation("REPLAN_DAY")
                        .scope("DAY")
                        .dayNumber(day)
                        .targetDayReference("第" + day + "天")
                        .clarificationQuestion("你想怎样重新规划第 " + day + " 天？请告诉我你的具体偏好（例如：“多安排室内避雨景点”、“少走路轻松游”、“亲子适合”等）。")
                        .requiresClarification(true)
                        .rawMessage(text)
                        .confidence(0.92)
                        .build();
            }
            if (isOption3) {
                int day = targetDay != null ? targetDay : activeDay;
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.REDUCE_DAY_DENSITY)
                        .operation("REDUCE_DAY_DENSITY")
                        .scope("DAY")
                        .dayNumber(day)
                        .reduceCount(1)
                        .rawMessage(text)
                        .confidence(0.92)
                        .build();
            }
            if (isOption4) {
                int day = targetDay != null ? targetDay : activeDay;
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.CLARIFICATION)
                        .operation("ADD_STOP")
                        .scope("DAY")
                        .dayNumber(day)
                        .targetDayReference("第" + day + "天")
                        .clarificationQuestion("请告诉我你想在第 " + day + " 天添加哪个景点？（例如：“加上三峡博物馆”或“添加鹅岭二厂”）。")
                        .requiresClarification(true)
                        .rawMessage(text)
                        .confidence(0.92)
                        .build();
            }
        }

        // 2. 景点替换与重排意图 (REPLACE_STOP / REPLAN_DAY)
        boolean hasReplaceKeywords = text.matches(".*(不想去|换一个|替换|换掉|有没有替换|有替换方案吗|换个地方|推荐其他|改一下|改一改|调整一下|调整|重新排).*")
                || text.matches(".*(改|调整).*(第[一二三四五六七八九十\\d]+天|今天|明天|首日|末日|最后一天).*");
        if (hasReplaceKeywords) {
            // A. 如果是针对整个某天的宽泛调整（如“把第二天景点改一下”、“改第二天景点”、“调整第二天”、“第二天行程改一下”）且没有指定具体景点与具体偏好
            boolean isDayScope = text.matches(".*(第[一二三四五六七八九十\\d]+天|今天|明天|首日|末日|最后一天).*(景点|行程|安排|路线)?.*(改一下|改一改|调整一下|调整|改改|改)?.*")
                    || text.matches(".*(改|调整).*(第[一二三四五六七八九十\\d]+天|今天|明天|首日|末日|最后一天).*");
            if (isDayScope && targetStop == null && matchedVenueId == null && preferences.isEmpty() && conditions.isEmpty()) {
                int day = targetDay != null ? targetDay : activeDay;
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.CLARIFICATION)
                        .operation("REPLAN_DAY")
                        .scope("DAY")
                        .dayNumber(day)
                        .targetDayReference("第" + day + "天")
                        .missingFields(List.of("REPLAN_PURPOSE"))
                        .clarificationQuestion("你想怎样调整第 " + day + " 天的行程？\n\n1. 重新规划第 " + day + " 天全部景点（如偏好少走路/室内避雨）\n2. 替换第 " + day + " 天的某一个具体景点\n3. 减少第 " + day + " 天的景点数量（行程更轻松）\n4. 添加新的景点")
                        .requiresClarification(true)
                        .rawMessage(text)
                        .confidence(0.9)
                        .build();
            }

            // B. 如果明确要替换具体景点或当前选中景点
            if (targetStop != null || matchedVenueId != null) {
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.SUGGEST_REPLACEMENTS)
                        .operation("REPLACE_STOP")
                        .scope("STOP")
                        .dayNumber(targetDay)
                        .targetStopId(targetStop)
                        .targetStopReference(mentionsSelectedStop ? "当前景点" : extractMentionedVenueAlias(text))
                        .preferences(preferences)
                        .conditions(conditions)
                        .pinnedStopIds(new ArrayList<>(pinned))
                        .rawMessage(text)
                        .confidence(0.9)
                        .build();
            }

            // C. 若想换点但未指定景点也未选中卡片
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.CLARIFICATION)
                    .operation("REPLACE_STOP")
                    .scope("STOP")
                    .dayNumber(targetDay)
                    .missingFields(List.of("TARGET_STOP"))
                    .clarificationQuestion("请问您想替换第 " + (targetDay != null ? targetDay : activeDay) + " 天的哪一个景点？您可以点击卡片选中，或直接输入景点名称。")
                    .requiresClarification(true)
                    .rawMessage(text)
                    .confidence(0.85)
                    .build();
        }

        // 3. 添加景点或餐饮 (ADD_STOP)
        if (text.matches(".*(加上|添加|新增|加入|放进|排入|安排上|想去|想吃|想喝|去吃|去喝|来点|来个|安排个|加个|多安排|多去|把.*加到|增加|补充|补上|纳入).*") && !text.contains("不想去") && !text.contains("删除") && !text.contains("去掉")) {
            if (targetStop != null && mentionsSelectedStop) {
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.ADD_STOP)
                        .operation("ADD_STOP")
                        .scope("DAY")
                        .dayNumber(explicitDay)
                        .targetStopId(targetStop)
                        .preferences(preferences)
                        .conditions(conditions)
                        .pinnedStopIds(new ArrayList<>(pinned))
                        .rawMessage(text)
                        .confidence(0.9)
                        .build();
            }

            if (matchedVenueId != null || !preferences.isEmpty()) {
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.ADD_STOP)
                        .operation("ADD_STOP")
                        .scope("DAY")
                        .dayNumber(explicitDay)
                        .targetAttractionId(matchedVenueId)
                        .requestedVenueName(matchedVenueId)
                        .preferences(preferences)
                        .conditions(conditions)
                        .pinnedStopIds(new ArrayList<>(pinned))
                        .rawMessage(text)
                        .confidence(0.9)
                        .build();
            }

            // 若提到了具体的景点名称
            Matcher addPoiMatcher = Pattern.compile("(?:加上|添加|加入|放进|排入|安排上|想去|想吃|想喝|去吃|去喝|来点|来个|安排个|加个|多安排|多去|把|增加)(?:一个)?(.+?)(?:加到|放到|排进|吧|啊|呢|。|！|!|\\?|？|$)").matcher(text);
            if (addPoiMatcher.find()) {
                String potentialPoi = addPoiMatcher.group(1).trim();
                // 剔除后缀噪声词，如“的景点”、“景点”、“打卡点”
                potentialPoi = potentialPoi.replaceAll("(?:的景点|的打卡点|景点|打卡点)+$", "").trim();
                if (!potentialPoi.isBlank() && !potentialPoi.contains("地方") && !potentialPoi.contains("什么")) {
                    String dynamicVenueId = extractMentionedVenueId(potentialPoi);
                    if (dynamicVenueId != null) {
                        return PlanAdjustmentIntent.builder()
                                .type(ConversationIntentType.ADD_STOP)
                                .operation("ADD_STOP")
                                .scope("DAY")
                                .dayNumber(explicitDay)
                                .targetAttractionId(dynamicVenueId)
                                .requestedVenueName(potentialPoi)
                                .preferences(preferences)
                                .conditions(conditions)
                                .pinnedStopIds(new ArrayList<>(pinned))
                                .rawMessage(text)
                                .confidence(0.92)
                                .build();
                    }
                    if (potentialPoi.matches(".*(甜点|甜食|糖水|小吃|美食|茶馆|咖啡|下午茶|小憩|火锅|老火锅|早点|餐饮|歇脚).*")) {
                        List<String> diningPrefs = new ArrayList<>(preferences);
                        if (!diningPrefs.contains("FOOD")) diningPrefs.add("FOOD");
                        return PlanAdjustmentIntent.builder()
                                .type(ConversationIntentType.ADD_STOP)
                                .operation("ADD_STOP")
                                .scope("DAY")
                                .dayNumber(explicitDay)
                                .requestedVenueName(potentialPoi)
                                .preferences(diningPrefs)
                                .conditions(conditions)
                                .pinnedStopIds(new ArrayList<>(pinned))
                                .rawMessage(text)
                                .confidence(0.92)
                                .build();
                    }
                    // 外部或小众 POI（如重邮四教、川美涂鸦街等），通过 ADD_STOP 传递给规划引擎由高德检索动态兜底
                    return PlanAdjustmentIntent.builder()
                            .type(ConversationIntentType.ADD_STOP)
                            .operation("ADD_STOP")
                            .scope("DAY")
                            .dayNumber(explicitDay)
                            .requestedVenueName(potentialPoi)
                            .preferences(preferences)
                            .conditions(conditions)
                            .pinnedStopIds(new ArrayList<>(pinned))
                            .rawMessage(text)
                            .confidence(0.92)
                            .build();
                }
            }

            // 未说明增加哪个景点
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.CLARIFICATION)
                    .operation("ADD_STOP")
                    .scope("DAY")
                    .dayNumber(targetDay)
                    .missingFields(List.of("VENUE_OR_PREFERENCE"))
                    .clarificationQuestion("你想在第 " + (targetDay != null ? targetDay : activeDay) + " 天增加什么类型的景点？（例如：室内场馆、自然风光、地道美食，或直接输入景点名称如“北仓”）")
                    .requiresClarification(true)
                    .rawMessage(text)
                    .confidence(0.85)
                    .build();
        }

        // 4. 删除景点 (REMOVE_STOP)
        if (text.matches(".*(删除|去掉|不要|取消|移除).*(景点|站点|卡片|这个).*") || text.startsWith("删除") || text.startsWith("去掉") || text.startsWith("移除")) {
            if (targetStop != null) {
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.REMOVE_STOP)
                        .operation("REMOVE_STOP")
                        .scope("STOP")
                        .dayNumber(targetDay)
                        .targetStopId(targetStop)
                        .pinnedStopIds(new ArrayList<>(pinned))
                        .rawMessage(text)
                        .confidence(0.9)
                        .build();
            }
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.CLARIFICATION)
                    .operation("REMOVE_STOP")
                    .scope("STOP")
                    .dayNumber(targetDay)
                    .missingFields(List.of("TARGET_STOP"))
                    .clarificationQuestion("请问您想移除第 " + (targetDay != null ? targetDay : activeDay) + " 天的哪个景点？请点击卡片选中或输入景点名称。")
                    .requiresClarification(true)
                    .rawMessage(text)
                    .confidence(0.85)
                    .build();
        }

        // 5. 雨天等天气条件重排 (REPLAN_DAY_FOR_CONDITION)
        if (text.matches(".*(下雨|雨天|避雨|暴雨|室外太热|太晒|避暑).*")) {
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.REPLAN_DAY_FOR_CONDITION)
                    .operation("REPLAN_DAY")
                    .scope("DAY")
                    .dayNumber(targetDay)
                    .condition("RAIN")
                    .conditions(List.of("RAIN"))
                    .preferences(preferences)
                    .pinnedStopIds(new ArrayList<>(pinned))
                    .rawMessage(text)
                    .confidence(0.95)
                    .build();
        }

        boolean hasAdjustmentKeywords = text.matches(".*(?:在当前行程草稿中|在行程中)?(?:补充|新增|添加|增加|加上|加入|放入|排入|换成|替换|改成|删除|去掉|减少|调整|修改|重新规划|重排).*");

        boolean isPlaceQuestion = !hasAdjustmentKeywords && (text.matches(".*(为什么|推荐理由|怎么走|几点|门票|多少钱|好吃|好玩|开灯|什么时候|有何特色|值不值得|注意事项|评价|介绍一下|好走吗|方便吗|平不平).*")
                || text.matches(".*(?:适合|能否|可以).*(?:吗|呢|不|谁|人群).*")
                || text.matches(".*(?:有什么|什么).*(?:历史|特色|亮点|好玩|推荐).*")
                || text.endsWith("？") || text.endsWith("?"));

        // 5.1 身体受限 / 手术康复 / 行动不便 / 轮椅无障碍低体力减负意图
        boolean isHealthOrMobilityLimitation = !isPlaceQuestion && text.matches(".*(手术|康复|行动不便|不方便走路|不便走路|走不了|走不动|不能走|腿脚|膝盖|腰疼|拐杖|轮椅|推车|老人|孕妇|带娃|脚痛|脚扭|体力不支|累死).*");
        if (isHealthOrMobilityLimitation) {
            List<String> healthPrefs = new ArrayList<>(preferences);
            addIfMissing(healthPrefs, true, "LOW_WALKING");
            addIfMissing(healthPrefs, true, "ACCESSIBILITY");
            int count = extractCount(text, 2);
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.REDUCE_DAY_DENSITY)
                    .operation("REDUCE_DENSITY")
                    .scope("DAY")
                    .dayNumber(targetDay)
                    .reduceCount(count)
                    .preferences(healthPrefs)
                    .conditions(List.of("ACCESSIBILITY"))
                    .pinnedStopIds(new ArrayList<>(pinned))
                    .rawMessage(text)
                    .confidence(0.98)
                    .build();
        }

        // 6. 减少当天景点密度 (REDUCE_DAY_DENSITY)
        if (!isPlaceQuestion && text.matches(".*(太赶|太累|太满|走不动|喘口气|少推荐|少安排|少去|少逛|少看|减少景点|轻松一点|节奏慢一点|少一个).*")) {
            int count = extractCount(text, 1);
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.REDUCE_DAY_DENSITY)
                    .operation("REDUCE_DENSITY")
                    .scope("DAY")
                    .dayNumber(targetDay)
                    .reduceCount(count)
                    .pinnedStopIds(new ArrayList<>(pinned))
                    .rawMessage(text)
                    .confidence(0.9)
                    .build();
        }

        // 7. 重排某天 (REPLAN_DAY)
        if (text.matches(".*(重新安排|重新规划|重新排|重排).*")) {
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.REPLAN_DAY)
                    .operation("REPLAN_DAY")
                    .scope("DAY")
                    .dayNumber(targetDay)
                    .preferences(preferences)
                    .conditions(conditions)
                    .pinnedStopIds(new ArrayList<>(pinned))
                    .rawMessage(text)
                    .confidence(0.9)
                    .build();
        }

        // 8. 景点问答 (PLACE_QUESTION / QA)
        if (isPlaceQuestion) {
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.PLACE_QUESTION)
                    .operation("QA")
                    .scope("STOP")
                    .targetStopId(targetStop)
                    .targetAttractionId(matchedVenueId)
                    .rawMessage(text)
                    .confidence(0.9)
                    .build();
        }

        // 9. 模糊愿望 / 通用优化要求 (CLARIFICATION)
        if (text.matches(".*(帮我把行程变得更好|优化一下|行程调整|更好一点|更合理一点|建议).*")) {
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.CLARIFICATION)
                    .operation("CLARIFICATION")
                    .rawMessage(text)
                    .clarificationQuestion("请告诉我您希望怎样调整行程？例如：在某天增加或替换景点、避开室外雨天、减少步行强度或放慢节奏。")
                    .requiresClarification(true)
                    .confidence(0.85)
                    .build();
        }

        // 10. 初始规划或周边规划意图 (PLAN)
        boolean hasPlanningKeywords = text.matches(".*(规划|旅游规划|行程|攻略|带我玩|怎么玩|玩两天|玩三天|两日游|三日游|自由行|旅游路线|旅行路线).*")
                || text.matches(".*(?:我在|人在|现在在|目前在|从).*(?:规划|推荐|路线|安排|半天|小时|钟头|时|分钟|天).*")
                || text.matches(".*(?:附近|周边).*(?:逛逛|转转|玩玩|看看|走走|景点|推荐).*");
        if (hasPlanningKeywords) {
            String startPlace = null;
            Matcher spMatcher = Pattern.compile("(?:我在|现在在|目前在|位于|人在|位置在|定位在|从)\\s*([^，。；;\\n]+?)(?=\\s*(?:为我|给我|帮我|请|想|要|需要|做|做个|来个|整点)?\\s*(?:规划|推荐|安排|方案|路线|旅游|出行)|\\s*(?:从)?(?:早上|早晨|上午|中午|下午|傍晚|晚上|夜间)?\\s*\\d{1,2}(?::\\d{2})?(?:点|时)?(?:半)?\\s*(?:到|至|\\-|~|——|—)|\\s*(?:给我|帮我|请|想|要|需要|规划|安排|推荐|限定|限时|只|有|剩|用|花|玩|游玩|的)?\\s*(?:半天|(?:\\d+(?:\\.\\d+)?|[一二两三四五六七八九十百]+)\\s*(?:个)?\\s*(?:小时|钟头|时|分钟|天))|[，。；;\\n]|$)").matcher(text);
            if (spMatcher.find()) {
                startPlace = spMatcher.group(1).trim()
                        .replaceAll("\\s*(?:从)?(?:早上|早晨|上午|中午|下午|傍晚|晚上|夜间)?\\s*\\d{1,2}(?::\\d{2})?(?:点|时)?(?:半)?\\s*(?:到|至|\\-|~|——|—).*", "")
                        .replaceFirst("(?:为我|给我|帮我|请|想|要)?(?:规划|安排|推荐|玩玩|玩耍|旅游|出行).*$", "")
                        .trim();
            }

            Integer timeBudgetMinutes = null;
            Matcher tbMatcher = Pattern.compile("(半天|(?:\\d+(?:\\.\\d+)?|[一二两三四五六七八九十百]+)\\s*(?:个)?\\s*(?:小时|钟头|时|分钟))").matcher(text);
            if (tbMatcher.find()) {
                String tbStr = tbMatcher.group(1);
                if (tbStr.contains("半天")) {
                    timeBudgetMinutes = 240;
                } else if (tbStr.contains("小时") || tbStr.contains("钟头") || tbStr.contains("时")) {
                    Matcher num = Pattern.compile("(\\d+|[一二两三四五六七八九十]+)").matcher(tbStr);
                    if (num.find()) {
                        Integer parsed = parseChineseNumber(num.group(1));
                        if (parsed != null) timeBudgetMinutes = parsed * 60;
                    }
                } else if (tbStr.contains("分钟")) {
                    Matcher num = Pattern.compile("(\\d+|[一二两三四五六七八九十]+)").matcher(tbStr);
                    if (num.find()) {
                        Integer parsed = parseChineseNumber(num.group(1));
                        if (parsed != null) timeBudgetMinutes = parsed;
                    }
                }
            }
            if (timeBudgetMinutes == null) {
                Matcher tiMatcher = Pattern.compile("(?:从)?\\s*(早上|早晨|上午|中午|下午|傍晚|晚上|夜间)?\\s*(\\d{1,2}(?::\\d{2})?|[一二两三四五六七八九十百]+)(?:点|时)?(半)?\\s*(?:到|至|\\-|~|——|—)\\s*(早上|早晨|上午|中午|下午|傍晚|晚上|夜间)?\\s*(\\d{1,2}(?::\\d{2})?|[一二两三四五六七八九十百]+)(?:点|时)?(半)?").matcher(text);
                if (tiMatcher.find()) {
                    int sMin = parseMinutesFromTime(tiMatcher.group(1), tiMatcher.group(2), tiMatcher.group(3));
                    int eMin = parseMinutesFromTime(tiMatcher.group(4), tiMatcher.group(5), tiMatcher.group(6));
                    if (sMin >= 0 && eMin >= 0) {
                        if (eMin < sMin && eMin <= 12 * 60) eMin += 12 * 60;
                        if (eMin > sMin) timeBudgetMinutes = eMin - sMin;
                    }
                }
            }

            Integer durationDays = null;
            Matcher daysMatcher = Pattern.compile("([一二两三四五六七八九十\\d]+)\\s*天|([一二两三四五六七八九十\\d]+)\\s*日游").matcher(text);
            if (daysMatcher.find()) {
                String dGroup = daysMatcher.group(1) != null ? daysMatcher.group(1) : daysMatcher.group(2);
                durationDays = parseChineseNumber(dGroup);
            } else if (timeBudgetMinutes != null && timeBudgetMinutes > 0) {
                durationDays = 1;
            }

            boolean missingStartForNearby = (text.contains("附近") || text.contains("周边")) && (startPlace == null || startPlace.isBlank());
            if (missingStartForNearby) {
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.PLAN)
                        .operation("PLAN")
                        .scope("TRIP")
                        .timeBudgetMinutes(timeBudgetMinutes)
                        .durationDays(durationDays)
                        .preferences(preferences)
                        .conditions(conditions)
                        .missingFields(List.of("START_PLACE"))
                        .clarificationQuestion("请问您当前在哪个位置？告诉我您的出发点，我来为您规划附近路线。")
                        .requiresClarification(true)
                        .rawMessage(text)
                        .confidence(0.9)
                        .build();
            }

            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.PLAN)
                    .operation("PLAN")
                    .scope("TRIP")
                    .startPlace(startPlace)
                    .timeBudgetMinutes(timeBudgetMinutes)
                    .durationDays(durationDays)
                    .preferences(preferences)
                    .conditions(conditions)
                    .rawMessage(text)
                    .confidence(0.9)
                    .build();
        }

        return PlanAdjustmentIntent.builder()
                .type(ConversationIntentType.UNKNOWN)
                .operation("UNKNOWN")
                .rawMessage(text)
                .clarificationQuestion("抱歉，我没有完全理解您的要求。您可以尝试说“推荐替换方案”、“推荐周边特色美食”、“今天下雨少安排室外”、或“行程放轻松一点”。")
                .requiresClarification(true)
                .confidence(0.5)
                .build();
    }

    private static void addIfMissing(List<String> values, boolean condition, String value) {
        if (condition && !values.contains(value)) values.add(value);
    }

    private boolean hasOtherVenue(String text, String selectedStopId) {
        return aliasMatcher.containsAnotherVenue(text, selectedStopId);
    }

    private List<String> extractPreferences(String text) {
        List<String> prefs = new ArrayList<>();
        if (text.matches(".*(室内|避雨|馆内|博物馆|展馆).*")) prefs.add("INDOOR");
        if (text.matches(".*(少走路|平街|轻松|不爬坡|不费力|低体力|缓坡|低步行|不方便走路|不便走路|走不了|走不动|不能走|手术|康复|腿脚|膝盖|腰疼|轮椅|拐杖|推车|带娃|老人|孕妇|脚痛|脚扭).*")) prefs.add("LOW_WALKING");
        if (text.matches(".*(手术|康复|腿脚|膝盖|腰疼|轮椅|拐杖|不便|残障|行动不便|骨折|病后).*")) prefs.add("ACCESSIBILITY");
        if (text.matches(".*(人文|历史|古镇|老街|遗址|古迹|红色|抗战).*")) prefs.add("HISTORY");
        if (text.matches(".*(夜景|江景|两江|灯光|临江).*")) prefs.add("NIGHT_VIEW");
        if (text.matches(".*(美食|小吃|火锅|江湖菜|泉水鸡|正餐|晚餐|午餐|餐厅|特色菜|烧烤|串串|辣味|吃|甜点|甜食|糖水|冰粉|凉糕|点心|茶歇|下午茶|咖啡|老茶馆|早点|小憩|歇脚).*")) prefs.add("FOOD");
        if (text.matches(".*(自然|峡谷|天坑|山水|森林|公园).*")) prefs.add("NATURE");
        if (text.matches(".*(亲子|孩子|科普|动物园).*")) prefs.add("FAMILY");
        if (text.matches(".*(同片区|同区|同行政区|本片区|附近片区).*")) prefs.add("SAME_DISTRICT");
        if (text.matches(".*(小众|平替|秘境|冷门).*")) prefs.add("NICHE");
        return prefs;
    }

    private List<String> extractConditions(String text) {
        List<String> conds = new ArrayList<>();
        if (text.matches(".*(下雨|雨天|避雨|暴雨).*")) conds.add("RAIN");
        if (text.matches(".*(太热|太晒|避暑|高温).*")) conds.add("HIGH_TEMPERATURE");
        return conds;
    }

    public String extractMentionedVenueId(String text) {
        return aliasMatcher.extractMentionedVenueId(text);
    }

    public String extractMentionedVenueAlias(String text) {
        return aliasMatcher.extractMentionedVenueAlias(text);
    }

    private String cleanVenueText(String text) {
        if (text == null) return null;
        String cleaned = text.trim().replaceFirst("^[：:，,、\\s]+", "");
        cleaned = cleaned.replaceFirst("[。！!？?；;，,、]+$", "").trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private int extractCandidateIndex(String text) {
        if (text.contains("第一") || text.contains("第1") || text.contains("方案一") || text.contains("方案1")) return 1;
        if (text.contains("第二") || text.contains("第2") || text.contains("方案二") || text.contains("方案2")) return 2;
        if (text.contains("第三") || text.contains("第3") || text.contains("方案三") || text.contains("方案3")) return 3;
        if (text.contains("第四") || text.contains("第4") || text.contains("方案四") || text.contains("方案4")) return 4;
        if (text.contains("第五") || text.contains("第5") || text.contains("方案五") || text.contains("方案5")) return 5;
        return 1;
    }

    public static Integer extractExplicitDayNumber(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = GENERIC_DAY_PATTERN.matcher(text);
        if (m.find()) {
            String chineseOrDigit = m.group(1) != null ? m.group(1) : m.group(2);
            Integer parsed = parseChineseNumber(chineseOrDigit);
            if (parsed != null && parsed >= 1 && parsed <= 30) return parsed;
        }
        if (text.contains("首日")) return 1;
        return null;
    }

    public static int extractDayNumber(String text, int defaultDay) {
        Integer explicit = extractExplicitDayNumber(text);
        if (explicit != null) return explicit;
        if (text != null) {
            if (text.contains("今天")) return defaultDay;
            if (text.contains("明天")) return defaultDay + 1;
        }
        return defaultDay;
    }

    public static Integer parseChineseNumber(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException ignored) {}
        String s = str.trim();
        if (s.length() == 1) {
            return switch (s) {
                case "一", "1" -> 1;
                case "二", "两", "2" -> 2;
                case "三", "3" -> 3;
                case "四", "4" -> 4;
                case "五", "5" -> 5;
                case "六", "6" -> 6;
                case "七", "7" -> 7;
                case "八", "8" -> 8;
                case "九", "9" -> 9;
                case "十", "10" -> 10;
                default -> null;
            };
        }
        if (s.equals("十")) return 10;
        if (s.startsWith("十")) {
            Integer sub = parseChineseNumber(s.substring(1));
            return 10 + (sub != null ? sub : 0);
        }
        if (s.endsWith("十")) {
            Integer sub = parseChineseNumber(s.substring(0, s.length() - 1));
            return (sub != null ? sub : 1) * 10;
        }
        if (s.contains("十")) {
            String[] parts = s.split("十", -1);
            Integer p1 = parseChineseNumber(parts[0]);
            Integer p2 = parts.length > 1 && !parts[1].isBlank() ? parseChineseNumber(parts[1]) : 0;
            return (p1 != null ? p1 : 1) * 10 + (p2 != null ? p2 : 0);
        }
        return null;
    }

    public static int parseMinutesFromTime(String period, String timeStr, String halfStr) {
        if (timeStr == null || timeStr.isBlank()) return -1;
        double hour = 0;
        int minute = 0;
        if (timeStr.contains(":")) {
            String[] parts = timeStr.split(":", 2);
            Integer h = parseChineseNumber(parts[0].trim());
            hour = h != null ? h : 0;
            if (parts.length > 1 && !parts[1].isBlank()) {
                try { minute = Integer.parseInt(parts[1].trim()); } catch (NumberFormatException ignored) {}
            }
        } else {
            Integer h = parseChineseNumber(timeStr.trim());
            hour = h != null ? h : 0;
        }
        if ("半".equals(halfStr)) {
            minute += 30;
        }

        if (period != null) {
            if ((period.contains("下午") || period.contains("晚上") || period.contains("傍晚") || period.contains("夜间")) && hour < 12) {
                hour += 12;
            } else if (period.contains("中午") && hour < 11) {
                hour += 12;
            }
        }
        return (int) Math.round(hour * 60 + minute);
    }

    private int extractCount(String text, int defaultCount) {
        Matcher m = COUNT_PATTERN.matcher(text);
        if (m.find()) {
            String val = m.group(1);
            Integer num = parseChineseNumber(val);
            if (num != null) return num;
        }
        if (text.contains("少两个") || text.contains("少去两个") || text.contains("少逛两个") || text.contains("减两个")) return 2;
        return defaultCount;
    }
}
