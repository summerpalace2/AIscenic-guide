package com.ai.guide.domain.language;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Shared language polarity and clause handling used across conversational domains. */
public final class IntentLanguagePolicy {
    private IntentLanguagePolicy() {}
    private static final Pattern NEGATIVE = Pattern.compile("(?:不想|不喜欢|不爱|不吃|不去|不要|不用|无需|别|不需要|不考虑|不安排|不添加|不加入|不选|不删除|不移除|不替换|不是)");
    private static final Pattern PRESERVE = Pattern.compile("(?:保持不动|保持原样|不变|别动|不要动|不调整|不修改|不优化|保留)");
    // An operation label can be a classifier guess. A new command also needs an
    // action in the user's text; dates, clocks and preference-only replies are slots.
    private static final Pattern ADJUSTMENT_ACTION = Pattern.compile(
            "添加|加入|加上|新增|加(?:[一二两三四五六七八九十\\d]+)?个|删除|移除|去掉|删掉|替换|(?:换成|换为|改成|改为)\\s*[^\\s，,。；;！!]+|"
            + "减少|删减|减掉|少(?:推荐|安排|排|去|逛|看)|重排|重新规划|重新安排|优化");
    public static boolean hasAdjustmentAction(String text) {
        return ADJUSTMENT_ACTION.matcher(positiveText(text)).find();
    }

    public static List<String> clauses(String text) {
        String normalized = (text == null ? "" : text).replaceAll("(?:不要|别|不)(?:爬坡|爬台阶|走太多路)", "少走路");
        return Arrays.stream(normalized.split("[，,。；;！!\\n]|(?:但是|不过|而是|但|只想|只要|而要|并且|同时)|(?<!不)(?<!不要)(?<!别)(?=想吃|想去|希望|请优化)|(?:并|再)(?=优化|重排|添加|加入|删除|移除|替换)|只(?=优化|重排|添加|加入|删除|移除|替换)"))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
    }
    public static String positiveText(String text) {
        return clauses(text).stream().filter(s -> !NEGATIVE.matcher(s).find() && !PRESERVE.matcher(s).find())
                .reduce((a, b) -> a + "，" + b).orElse("");
    }
    public static List<String> preservedReferences(String text) {
        return clauses(text).stream().filter(s -> PRESERVE.matcher(s).find()).toList();
    }
    public static List<String> distinctActionClauses(String text) {
        var actions = new java.util.LinkedHashMap<String, String>();
        var all = new java.util.ArrayList<String>();
        for (String clause : clauses(positiveText(text))) {
            boolean action = false;
            if (clause.matches(".*(?:替换|换成|换为|改成|改为).*")) { actions.putIfAbsent("REPLACE",clause); action=true; }
            if (clause.matches(".*(?:添加|加入|加上|加个|新增).*")) { actions.putIfAbsent("ADD",clause); action=true; }
            if (clause.matches(".*(?:删除|移除|去掉|删掉).*")) { actions.putIfAbsent("REMOVE",clause); action=true; }
            if (clause.matches(".*(?:重排|重新规划|优化).*")) { actions.putIfAbsent("REPLAN",clause); action=true; }
            if (action) all.add(clause);
        }
        return all.size() > 1 ? List.copyOf(all) : List.of();
    }
    public static boolean explicitRepeat(String text) {
        return positiveText(text).matches("(?s).*(?:再次|再去|回到|返回|二次访问|重复访问).*" );
    }
}
