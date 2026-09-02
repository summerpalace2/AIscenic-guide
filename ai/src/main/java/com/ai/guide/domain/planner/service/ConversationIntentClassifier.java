package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.PlanPageContext;
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

    private static final Pattern COUNT_PATTERN = Pattern.compile("少(?:推荐|安排|去|逛|看)?\\s*([一二两123456789\\d]+)\\s*个");
    private static final Pattern CANDIDATE_INDEX_PATTERN = Pattern.compile("(?:换成|选|应用)第?\\s*([一二两三四五12345])\\s*个?(?:方案|候选)?");
    private static final Pattern REPLACEMENT_CONNECTOR_PATTERN = Pattern.compile("替换成|替换为|换成|换为|改成|改为|替代");
    private static final Pattern GENERIC_DAY_PATTERN = Pattern.compile("(?:第\\s*([一二三四五六七八九十\\d]+)\\s*天|day\\s*(\\d+))", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> CORE_ATTRACTIONS_ALIAS = Map.ofEntries(
            Map.entry("洪崖洞", "cq-hongyadong"),
            Map.entry("解放碑", "cq-jiefangbei"),
            Map.entry("三峡博物馆", "cq-museum"),
            Map.entry("博物馆", "cq-museum"),
            Map.entry("李子坝", "cq-liziba"),
            Map.entry("穿楼", "cq-liziba"),
            Map.entry("大剧院", "cq-grand-theatre"),
            Map.entry("科技馆", "cq-kejiguan"),
            Map.entry("磁器口", "cq-ciqikou"),
            Map.entry("魁星楼", "cq-kuixinglou"),
            Map.entry("十八梯", "cq-shibati"),
            Map.entry("二厂", "cq-erling"),
            Map.entry("鹅岭二厂", "cq-erling"),
            Map.entry("长江索道", "cq-changjiang-cable"),
            Map.entry("索道", "cq-changjiang-cable"),
            Map.entry("一棵树", "cq-nanshan-yikeshu"),
            Map.entry("南山一棵树", "cq-nanshan-yikeshu"),
            Map.entry("弹子石", "cq-danzi-shi"),
            Map.entry("弹子石老街", "cq-danzi-shi"),
            Map.entry("观音桥", "cq-guanyinqiao"),
            Map.entry("罗汉寺", "cq-luohan-temple"),
            Map.entry("湖广会馆", "cq-huguang-guild"),
            Map.entry("中山四路", "cq-zhongshan-road"),
            Map.entry("白鹤梁", "cq-baiheliang"),
            Map.entry("水下博物馆", "cq-baiheliang"),
            Map.entry("天生三桥", "cq-wulong-tiankeng"),
            Map.entry("武隆", "cq-wulong-tiankeng"),
            Map.entry("天坑", "cq-wulong-tiankeng"),
            Map.entry("大足石刻", "cq-dazu-rock"),
            Map.entry("宝顶山", "cq-dazu-rock"),
            Map.entry("融汇温泉", "cq-ronghui-hotspring"),
            Map.entry("南温泉", "cq-south-hotspring"),
            Map.entry("歌乐山", "cq-geleyuan"),
            Map.entry("渣滓洞", "cq-geleyuan"),
            Map.entry("白公馆", "cq-geleyuan"),
            Map.entry("马鞍山", "cq-maanshan"),
            Map.entry("北仓", "cq-beicang"),
            Map.entry("金佛山", "cq-jinfoshan")
    );

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
        Integer targetDay = extractDayNumber(text, activeDay);
        String matchedVenueId = extractMentionedVenueId(text);
        boolean mentionsSelectedStop = text.matches(".*(这个景点|当前景点|这个地方|这里|这个|当前站|此站).*");
        String targetStop = mentionsSelectedStop && selectedStop != null && !selectedStop.isBlank()
                ? selectedStop : (selectedStop != null && !selectedStop.isBlank() && !hasOtherVenue(text, selectedStop) ? selectedStop : null);

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

        // A normal chat answer can contain a narrative plan such as “方案A：文化室内线”.
        // When the user then says “选这个帮我改行程”, there is no server-side Proposal yet,
        // so this is a trip replan request rather than APPLY_REPLACEMENT.
        boolean selectsNarrativePlan = text.matches(".*(?:方案\\s*[A-Za-zＡ-Ｚ]|选(?:择)?(?:这个|它|这套|这条)|就(?:选|用)这个|按这个(?:方案)?|采用这个(?:方案)?).*")
                && text.matches(".*(?:改|调整|修改|应用|重排|重新规划|行程|安排).*");
        if (selectsNarrativePlan) {
            List<String> narrativePreferences = new ArrayList<>(preferences);
            addIfMissing(narrativePreferences, text.matches(".*(?:室内|文化室内|美术馆|博物馆|室内线).*"), "INDOOR");
            addIfMissing(narrativePreferences, text.matches(".*(?:少走路|文化室内|室内线|平街).*"), "LOW_WALKING");
            addIfMissing(narrativePreferences, text.matches(".*(?:人文|文化|历史|博物馆|美术馆).*"), "HISTORY");
            return PlanAdjustmentIntent.builder()
                    .type(ConversationIntentType.REPLAN_DAY)
                    .operation("REPLAN_DAY")
                    .scope("TRIP")
                    .preferences(narrativePreferences)
                    .conditions(new ArrayList<>(conditions))
                    .pinnedStopIds(new ArrayList<>(pinned))
                    .rawMessage(text)
                    .confidence(0.95)
                    .build();
        }

        // 用户可以沿着当前选中的站点继续提出“推荐同片区其他室内景点”这类候选请求。
        // 这不是普通问答，也不要求用户重复输入原景点；选中站点或上一份提案会提供替换源。
        boolean asksForAlternativeCandidates = text.matches(".*(?:推荐|提供|找|看看|换|替换).*(?:同片区|附近|其他|别的|更多|不同).*(?:景点|地点|室内|场馆|候选|备选|方案).*")
                || text.matches(".*(?:同片区|附近|其他|别的|更多|不同).*(?:景点|地点|室内|场馆).*(?:推荐|换|找|提供).*");
        if (asksForAlternativeCandidates) {
            // 快捷入口会把当前卡片名称写进文案，例如“请推荐【南温泉风景区】的
            // 同片区可替换景点”。这仍是在询问该卡片的替换候选，不能把名称误判
            // 为另一个目标而丢弃页面已选站点。
            if (selectedStop != null && !selectedStop.isBlank()) {
                targetStop = selectedStop;
            }
            if (targetStop == null || targetStop.isBlank()) {
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
                    .targetStopReference(mentionsSelectedStop ? "当前景点" : null)
                    .preferences(preferences)
                    .conditions(conditions)
                    .pinnedStopIds(new ArrayList<>(pinned))
                    .rawMessage(text)
                    .confidence(0.94)
                    .build();
        }

        // 1. 换成第 N 个候选 / 指定选项 (APPLY_REPLACEMENT)
        Matcher candidateMatcher = CANDIDATE_INDEX_PATTERN.matcher(text);
        if (candidateMatcher.find() || text.matches(".*(换成第[12345一二两三四五]个|选第[12345一二两三四五]个|方案[12345一二两三四五]|确认替换|应用方案).*")) {
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

        // 3. 添加景点 (ADD_STOP)
        if (text.matches(".*(加上|添加|加入|放进|排入|安排上|想去|加个|多安排|多去|把.*加到|增加).*") && !text.contains("不想去") && !text.contains("删除") && !text.contains("去掉")) {
            if (targetStop != null && mentionsSelectedStop) {
                return PlanAdjustmentIntent.builder()
                        .type(ConversationIntentType.ADD_STOP)
                        .operation("ADD_STOP")
                        .scope("DAY")
                        .dayNumber(targetDay)
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
                        .dayNumber(targetDay)
                        .targetAttractionId(matchedVenueId)
                        .requestedVenueName(matchedVenueId)
                        .preferences(preferences)
                        .conditions(conditions)
                        .pinnedStopIds(new ArrayList<>(pinned))
                        .rawMessage(text)
                        .confidence(0.9)
                        .build();
            }

            // 若提到了具体的外部景点名称但不在24大核心库中
            Matcher addPoiMatcher = Pattern.compile("(?:加上|添加|加入|放进|排入|安排上|想去|加个|多安排|多去|把)(.+?)(?:加到|放到|排进|吧|啊|呢|。|！|!|\\?|？|$)").matcher(text);
            if (addPoiMatcher.find()) {
                String potentialPoi = addPoiMatcher.group(1).trim();
                if (!potentialPoi.isBlank() && !potentialPoi.contains("景点") && !potentialPoi.contains("地方") && !potentialPoi.contains("什么")) {
                    return PlanAdjustmentIntent.builder()
                            .type(ConversationIntentType.CLARIFICATION)
                            .operation("ADD_STOP")
                            .scope("DAY")
                            .dayNumber(targetDay)
                            .missingFields(List.of("EXTERNAL_POI"))
                            .clarificationQuestion("当前规划引擎专注于重庆 24 大核心景点的精细化排程与耗时测算。您提到的“" + potentialPoi + "”暂未纳入 24 大核心景点库，建议从 24 大核心景点中选择（或作为后续加强版能力开放）。")
                            .requiresClarification(true)
                            .rawMessage(text)
                            .confidence(0.85)
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

        // 6. 减少当天景点密度 (REDUCE_DAY_DENSITY)
        if (text.matches(".*(太赶|太累|太满|走不动|喘口气|少推荐|少安排|少去|少逛|少看|减少景点|轻松一点|节奏慢一点|少一个).*")) {
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
        if (text.matches(".*(为什么|推荐理由|怎么走|几点|门票|多少钱|好吃|好玩|开灯|什么时候|介绍|历史|有何特色|值不值得|注意事项|评价|介绍一下).*")
                || text.endsWith("？") || text.endsWith("?")) {
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

        return PlanAdjustmentIntent.builder()
                .type(ConversationIntentType.UNKNOWN)
                .operation("UNKNOWN")
                .rawMessage(text)
                .clarificationQuestion("抱歉，我没有完全理解您的要求。您可以尝试说“推荐替换方案”、“加上三峡博物馆”、“今天下雨少安排室外”、或“少推荐一个景点”。")
                .requiresClarification(true)
                .confidence(0.5)
                .build();
    }

    private static void addIfMissing(List<String> values, boolean condition, String value) {
        if (condition && !values.contains(value)) values.add(value);
    }

    private static boolean hasOtherVenue(String text, String selectedStopId) {
        for (String alias : CORE_ATTRACTIONS_ALIAS.keySet()) {
            if (text.contains(alias)) return true;
        }
        return false;
    }

    private List<String> extractPreferences(String text) {
        List<String> prefs = new ArrayList<>();
        if (text.matches(".*(室内|避雨|馆内|博物馆|展馆).*")) prefs.add("INDOOR");
        if (text.matches(".*(少走路|平街|轻松|不爬坡|不费力|低体力|缓坡).*")) prefs.add("LOW_WALKING");
        if (text.matches(".*(人文|历史|古镇|老街|遗址|古迹|红色|抗战).*")) prefs.add("HISTORY");
        if (text.matches(".*(夜景|江景|两江|灯光|临江).*")) prefs.add("NIGHT_VIEW");
        if (text.matches(".*(美食|小吃|火锅|江湖菜|吃).*")) prefs.add("FOOD");
        if (text.matches(".*(自然|峡谷|天坑|山水|森林|公园).*")) prefs.add("NATURE");
        if (text.matches(".*(亲子|孩子|科普|动物园).*")) prefs.add("FAMILY");
        return prefs;
    }

    private List<String> extractConditions(String text) {
        List<String> conds = new ArrayList<>();
        if (text.matches(".*(下雨|雨天|避雨|暴雨).*")) conds.add("RAIN");
        if (text.matches(".*(太热|太晒|避暑|高温).*")) conds.add("HIGH_TEMPERATURE");
        return conds;
    }

    private String extractMentionedVenueId(String text) {
        if (text == null || text.isBlank()) return null;
        for (Map.Entry<String, String> entry : CORE_ATTRACTIONS_ALIAS.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String extractMentionedVenueAlias(String text) {
        if (text == null || text.isBlank()) return null;
        String matchedAlias = null;
        int matchedLength = -1;
        for (String alias : CORE_ATTRACTIONS_ALIAS.keySet()) {
            if (text.contains(alias) && alias.length() > matchedLength) {
                matchedAlias = alias;
                matchedLength = alias.length();
            }
        }
        return matchedAlias;
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

    public static int extractDayNumber(String text, int defaultDay) {
        if (text == null || text.isBlank()) return defaultDay;
        Matcher m = GENERIC_DAY_PATTERN.matcher(text);
        if (m.find()) {
            String chineseOrDigit = m.group(1) != null ? m.group(1) : m.group(2);
            Integer parsed = parseChineseNumber(chineseOrDigit);
            if (parsed != null && parsed >= 1 && parsed <= 30) return parsed;
        }
        if (text.contains("首日")) return 1;
        if (text.contains("今天")) return defaultDay;
        if (text.contains("明天")) return defaultDay + 1;
        return defaultDay;
    }

    public static Integer parseChineseNumber(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException ignored) {}
        return switch (str.trim()) {
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
