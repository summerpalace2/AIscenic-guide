package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.*;
import java.util.*;
import java.util.regex.Pattern;

/** Quantity is an execution slot, never a default invented by the model. */
public final class PlannerQuantityPolicy {
    private PlannerQuantityPolicy() { }
    private static final String NUMBER = "[一二两三四五六七八九十百\\d]+";
    private static final String UNIT = "(?:个(?:景点|站点)?(?!人)|处|站|景点)";
    private static final Pattern COUNT = Pattern.compile("(?:减少|删减|减掉|少(?:推荐|排|安排|去|逛|看)?)\\s*([负+\\-]?" + NUMBER + ")\\s*" + UNIT);
    private static final Pattern SHORT_COUNT = Pattern.compile("(?:就|只)?\\s*(" + NUMBER + ")\\s*" + UNIT + "(?:就好|即可|就行|吧)?");
    public static Integer count(String text) {
        String raw = text == null ? "" : text.trim().replaceAll("[，。！!？?]+$", "");
        var action = COUNT.matcher(raw);
        if (action.find()) {
            String number = action.group(1);
            if (number.startsWith("负") || number.startsWith("-") || number.startsWith("+")) return null;
            Integer result = ConversationIntentClassifier.parseChineseNumber(number);
            if (action.find()) return null;
            return result;
        }
        var shortReply = SHORT_COUNT.matcher(raw);
        return shortReply.matches() ? ConversationIntentClassifier.parseChineseNumber(shortReply.group(1)) : null;
    }
    public static boolean reductionRequest(String text) {
        return text != null && text.matches("(?s).*(?:减少|删减|少(?:推荐|排|安排|去|逛|看)?).*(?:景点|站|处|个|数量).*");
    }
    public static boolean retainRequest(String text) {
        return text != null && text.matches("(?s).*(?:只保留|仅保留|保留)\\s*(?:[一二两三四五六七八九十百几\\d]+|多少)\\s*(?:个|处|站|景点).*");
    }
    public static void normalize(PlanAdjustmentIntent intent) {
        if (intent == null) return;
        String raw = intent.getRawMessage();
        if (retainRequest(raw) && (intent.getType() == ConversationIntentType.REDUCE_DAY_DENSITY
                || intent.getType() == ConversationIntentType.CLARIFICATION
                || "REDUCE_DENSITY".equals(intent.getOperation()))) {
            intent.setType(ConversationIntentType.CLARIFICATION);
            intent.setOperation("REDUCE_DENSITY"); intent.setScope("DAY"); intent.setReduceCount(null);
            intent.setRequiresClarification(true); intent.setMissingFields(List.of("REDUCTION_REPHRASE"));
            intent.setClarificationQuestion("保留数量是调整后的总站数，不能当作删除数量。当前不支持按最终保留数调整，请改为明确减少多少站。");
            return;
        }
        if (intent.getType() != ConversationIntentType.REDUCE_DAY_DENSITY
                && !"REDUCE_DENSITY".equals(intent.getOperation()) && !"REDUCE_DAY_DENSITY".equals(intent.getOperation())) return;
        Integer count = count(raw);
        if (count == null || count < 1) {
            intent.setType(ConversationIntentType.CLARIFICATION); intent.setOperation("REDUCE_DENSITY");
            intent.setRequiresClarification(true); intent.setReduceCount(null);
            var missing = new ArrayList<>(intent.getMissingFields());
            missing.remove("REPLAN_PREFERENCE");
            if (!missing.contains("REDUCE_COUNT")) missing.add("REDUCE_COUNT");
            intent.setMissingFields(missing);
            intent.setClarificationQuestion("你希望减少多少个景点？请明确数量；我会保留固定站点。");
        } else {
            intent.setReduceCount(count);
            var missing = new ArrayList<>(intent.getMissingFields()); missing.remove("REDUCE_COUNT");
            intent.setMissingFields(missing);
            if (missing.isEmpty()) {
                intent.setType(ConversationIntentType.REDUCE_DAY_DENSITY);
                intent.setRequiresClarification(false); intent.setClarificationQuestion(null);
            }
        }
    }
}
