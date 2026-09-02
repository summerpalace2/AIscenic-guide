package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.ConstraintConflict;
import com.ai.guide.domain.planner.model.ConstraintOrigin;
import com.ai.guide.domain.planner.model.TravelConstraints;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户出行约束自然语言解析器
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 */
@Service
public class TravelConstraintParser implements TravelConstraintParserPort {

    private static final Pattern ARRIVAL = Pattern.compile("((?:周|星期)[一二三四五六日天])\\s*(上午|中午|下午|傍晚|晚上|夜间)?(?:到重庆|抵达重庆|到达重庆)");
    private static final Pattern DEPARTURE = Pattern.compile("((?:周|星期)[一二三四五六日天])\\s*(上午|中午|下午|傍晚|晚上|夜间)?(?:离开|返程|回程|回重庆以外|回家|回)");
    private static final Pattern DAYS = Pattern.compile("([一二三四五六七八九十\\d]+)\\s*天");
    private static final Pattern AMOUNT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*元");
    private static final Pattern STAY = Pattern.compile("(?:住在|住宿|酒店在)\\s*([^，。；;]+)");
    private static final Pattern MUST_VISIT = Pattern.compile("(?:必须|一定要|务必)(?:去|看|逛|体验)?\\s*([^，。；;]+)");

    private static final List<Keyword> INTERESTS = List.of(
            new Keyword("自然奇观", "自然"), new Keyword("自然", "自然"),
            new Keyword("人文历史", "人文"), new Keyword("人文", "人文"), new Keyword("历史", "人文"),
            new Keyword("8D魔幻", "8D魔幻"), new Keyword("魔幻", "8D魔幻"),
            new Keyword("山城夜景", "夜景"), new Keyword("夜景", "夜景"),
            new Keyword("市井烟火", "市井"), new Keyword("市井", "市井"), new Keyword("烟火", "市井"),
            new Keyword("天然温泉", "温泉"), new Keyword("温泉", "温泉"),
            new Keyword("城市", "城市"), new Keyword("地标", "城市"),
            new Keyword("文创", "文创"), new Keyword("休闲", "休闲"),
            new Keyword("拍照", "拍照"), new Keyword("美食", "美食"),
            new Keyword("火锅", "美食"), new Keyword("本地菜", "本地菜"));

    private static final String NEGATION = "不喜欢|不想要|不要|不看|不去|不坐|不乘|不吃|避开|避免|排除|拒绝";

    private final ConstraintConflictDetector conflictDetector;
    private final LlmShadowPreferenceExtractor shadowExtractor;

    @Autowired
    public TravelConstraintParser(ConstraintConflictDetector conflictDetector,
                                  LlmShadowPreferenceExtractor shadowExtractor) {
        this.conflictDetector = conflictDetector;
        this.shadowExtractor = shadowExtractor;
    }

    /** Compatibility constructor for direct unit tests. */
    public TravelConstraintParser() {
        this(new ConstraintConflictDetector(), new LlmShadowPreferenceExtractor());
    }

    public TravelConstraints parse(String prompt) {
        String source = prompt == null ? "" : prompt.trim();
        Matcher arrival = ARRIVAL.matcher(source);
        Matcher departure = DEPARTURE.matcher(source);
        Matcher days = DAYS.matcher(source);
        Matcher amount = AMOUNT.matcher(source);
        Matcher stay = STAY.matcher(source);

        boolean arrivalFound = arrival.find();
        boolean departureFound = departure.find();
        boolean daysFound = days.find();
        boolean amountFound = amount.find();
        boolean stayFound = stay.find();

        String arrivalAt = arrivalFound ? arrival.group(1) + nullToEmpty(arrival.group(2)) : "未提供";
        String departureAt = departureFound ? departure.group(1) + nullToEmpty(departure.group(2)) : "未提供";

        int dowDays = calculateDaysFromDayOfWeek(arrivalAt, departureAt);
        int durationDays = 2;
        boolean durationSpecified = false;
        if (source.matches(".*(全天|单天|当天|1天|一天|一日|半天).*")) {
            durationDays = 1;
            durationSpecified = true;
        } else if (source.matches(".*(2天|两天|两日|二天).*")) {
            durationDays = 2;
            durationSpecified = true;
        } else if (source.matches(".*(3天|三天|三日).*")) {
            durationDays = 3;
            durationSpecified = true;
        } else if (source.matches(".*(4天|四天|四日).*")) {
            durationDays = 4;
            durationSpecified = true;
        } else if (source.matches(".*(5天|五天|五日).*")) {
            durationDays = 5;
            durationSpecified = true;
        } else if (daysFound) {
            durationDays = parseNumber(days.group(1));
            durationSpecified = true;
        } else if (dowDays > 0) {
            durationDays = dowDays;
            durationSpecified = true;
        }
        if (durationDays <= 0) durationDays = 2;

        Set<String> interests = new LinkedHashSet<>();
        Set<String> avoid = new LinkedHashSet<>();
        for (Keyword keyword : INTERESTS) {
            if (positiveKeyword(source, keyword.keyword())) interests.add(keyword.label());
            if (negatedKeyword(source, keyword.keyword())) avoid.add(keyword.label());
        }
        boolean explicitInterests = !interests.isEmpty();
        if (interests.isEmpty()) interests.addAll(List.of("城市", "人文", "夜景"));
        interests.removeAll(avoid);

        String budget = source.matches(".*(预算有限|预算友好|预算不高|省钱|经济).*")
                ? "有限" : amountFound ? "约 " + amount.group(1) + " 元 / 人 / 天" : "未提供";
        boolean explicitBudget = !"未提供".equals(budget);
        String stayArea = stayFound ? stay.group(1).trim() : "未提供";

        // Dietary restrictions take precedence over generic food keywords.
        String diet = positiveKeyword(source, "素食") ? "素食"
                : positiveKeyword(source, "清真") ? "清真"
                : positiveKeyword(source, "清淡不辣") || positiveKeyword(source, "清淡") ? "清淡不辣"
                : positiveKeyword(source, "九宫格老火锅") || positiveKeyword(source, "九宫格火锅") || positiveKeyword(source, "火锅") ? "重庆火锅"
                : positiveKeyword(source, "地道江湖菜") || positiveKeyword(source, "江湖菜") ? "地道江湖菜"
                : positiveKeyword(source, "街头小吃小面") || positiveKeyword(source, "小吃") || positiveKeyword(source, "小面") ? "街头小吃"
                : positiveKeyword(source, "本地菜优先") || positiveKeyword(source, "本地菜") || positiveKeyword(source, "美食") || positiveKeyword(source, "吃") ? "本地菜优先" : "未提供";
        boolean explicitDiet = !"未提供".equals(diet);

        String companions = positiveKeyword(source, "父母") || positiveKeyword(source, "长辈") || positiveKeyword(source, "老人") ? "带父母"
                : positiveKeyword(source, "亲子出游") || positiveKeyword(source, "亲子家庭") || positiveKeyword(source, "亲子") || positiveKeyword(source, "带小孩") || positiveKeyword(source, "小孩") || positiveKeyword(source, "孩子") ? "带孩子"
                : positiveKeyword(source, "情侣结伴") || positiveKeyword(source, "情侣双人") || positiveKeyword(source, "情侣") ? "情侣出游"
                : positiveKeyword(source, "朋友结伴") || positiveKeyword(source, "朋友") ? "朋友出游"
                : positiveKeyword(source, "独自出发") || positiveKeyword(source, "独自") || positiveKeyword(source, "一个人") || positiveKeyword(source, "自由行") ? "独自出发" : "未提供";
        boolean explicitCompanions = !"未提供".equals(companions);

        boolean lowWalking = source.matches(".*(少走路|不想走太多路|少爬坡|轻松少走台阶|轻松步行|少台阶).*");
        boolean highWalking = !lowWalking && source.matches(".*(不怕多走路|多走路|深度打卡|徒步).*");
        String walkingTolerance = lowWalking ? "低" : highWalking ? "高" : "正常";
        boolean explicitWalking = lowWalking || highWalking || source.matches(".*正常步行.*");

        boolean explicitTransit = positiveKeyword(source, "地铁") || positiveKeyword(source, "轻轨") || positiveKeyword(source, "轻轨地铁优先");
        boolean explicitPublicTransport = positiveKeyword(source, "公交") || positiveKeyword(source, "公共交通");
        boolean explicitTaxi = positiveKeyword(source, "打车") || positiveKeyword(source, "出租车") || positiveKeyword(source, "打车为主");
        String transport = explicitTransit ? "地铁优先"
                : explicitPublicTransport ? "公共交通优先"
                : explicitTaxi ? "打车优先" : "未提供";
        boolean explicitTransport = !"未提供".equals(transport);

        List<String> mustVisit = extractMustVisit(source);
        Map<String, ConstraintOrigin> origins = new LinkedHashMap<>();
        if (source.contains("重庆")) origins.put("destination", ConstraintOrigin.PROMPT);
        if (arrivalFound) origins.put("arrivalAt", ConstraintOrigin.PROMPT);
        if (departureFound) origins.put("departureAt", ConstraintOrigin.PROMPT);
        if (daysFound || durationSpecified) origins.put("durationDays", ConstraintOrigin.PROMPT);
        if (explicitCompanions) origins.put("companions", ConstraintOrigin.PROMPT);
        if (explicitWalking) origins.put("walkingTolerance", ConstraintOrigin.PROMPT);
        if (explicitBudget) origins.put("budget", ConstraintOrigin.PROMPT);
        if (explicitInterests) origins.put("interests", ConstraintOrigin.PROMPT);
        if (stayFound) origins.put("stayArea", ConstraintOrigin.PROMPT);
        if (explicitTransport) origins.put("transportPreference", ConstraintOrigin.PROMPT);
        if (explicitDiet) origins.put("dietPreference", ConstraintOrigin.PROMPT);
        if (!mustVisit.isEmpty()) origins.put("mustVisit", ConstraintOrigin.PROMPT);
        if (!avoid.isEmpty()) origins.put("avoid", ConstraintOrigin.PROMPT);

        List<String> missing = criticalMissing(arrivalFound, departureFound, daysFound || durationSpecified);
        LlmShadowPreferenceExtractor.ShadowExtractionResult shadow = shadowExtractor != null
                ? shadowExtractor.extractShadowPreferences(source)
                : null;

        return TravelConstraints.builder()
                .destination("重庆")
                .arrivalAt(arrivalAt)
                .departureAt(departureAt)
                .durationDays(durationDays)
                .companions(companions)
                .walkingTolerance(walkingTolerance)
                .budget(budget)
                .interests(new ArrayList<>(interests))
                .stayArea(stayArea)
                .transportPreference(transport)
                .dietPreference(diet)
                .mustVisit(new ArrayList<>(mustVisit))
                .avoid(new ArrayList<>(avoid))
                .criticalMissingFields(missing)
                .needsClarification(!missing.isEmpty())
                .shadowSemanticPreferences(shadow == null ? Map.of() : shadow.asMap())
                .rawPrompt(source)
                .origins(origins)
                .build();
    }

    private int calculateDaysFromDayOfWeek(String arrivalAt, String departureAt) {
        if (arrivalAt == null || departureAt == null || "未提供".equals(arrivalAt) || "未提供".equals(departureAt)) return 0;
        int arrDay = parseDayOfWeek(arrivalAt);
        int depDay = parseDayOfWeek(departureAt);
        if (arrDay > 0 && depDay > 0) {
            int diff = depDay >= arrDay ? (depDay - arrDay + 1) : (depDay + 7 - arrDay + 1);
            if (diff >= 1 && diff <= 7) return diff;
        }
        return 0;
    }

    private int parseDayOfWeek(String text) {
        if (text == null) return 0;
        if (text.contains("一")) return 1;
        if (text.contains("二")) return 2;
        if (text.contains("三")) return 3;
        if (text.contains("四")) return 4;
        if (text.contains("五")) return 5;
        if (text.contains("六")) return 6;
        if (text.contains("日") || text.contains("天")) return 7;
        return 0;
    }

    public TravelConstraints applyOverrides(TravelConstraints base, Map<String, Object> overrides) {
        if (base == null) base = new TravelConstraints();
        if (overrides == null || overrides.isEmpty()) return base.copy();
        TravelConstraints next = base.copy();
        if (setText(overrides, "destination", next::setDestination)) next.markOrigin("destination", ConstraintOrigin.REQUEST);
        if (setText(overrides, "arrivalAt", next::setArrivalAt)) next.markOrigin("arrivalAt", ConstraintOrigin.REQUEST);
        if (setText(overrides, "departureAt", next::setDepartureAt)) next.markOrigin("departureAt", ConstraintOrigin.REQUEST);
        if (setText(overrides, "companions", next::setCompanions)) next.markOrigin("companions", ConstraintOrigin.REQUEST);
        if (setText(overrides, "walkingTolerance", next::setWalkingTolerance)) next.markOrigin("walkingTolerance", ConstraintOrigin.REQUEST);
        if (setText(overrides, "budget", next::setBudget)) next.markOrigin("budget", ConstraintOrigin.REQUEST);
        if (setText(overrides, "stayArea", next::setStayArea)) next.markOrigin("stayArea", ConstraintOrigin.REQUEST);
        if (setText(overrides, "transportPreference", next::setTransportPreference)) next.markOrigin("transportPreference", ConstraintOrigin.REQUEST);
        if (setText(overrides, "dietPreference", next::setDietPreference)) next.markOrigin("dietPreference", ConstraintOrigin.REQUEST);
        setList(overrides, "interests", next::setInterests, next, "interests");
        setList(overrides, "mustVisit", next::setMustVisit, next, "mustVisit");
        setList(overrides, "avoid", next::setAvoid, next, "avoid");
        Object days = overrides.get("durationDays");
        if (days != null) {
            try {
                int parsed = Integer.parseInt(String.valueOf(days));
                if (parsed >= 1 && parsed <= 7) {
                    next.setDurationDays(parsed);
                    next.markOrigin("durationDays", ConstraintOrigin.REQUEST);
                }
            } catch (NumberFormatException ignored) { }
        }
        refreshMissing(next);
        if (conflictDetector != null) {
            List<ConstraintConflict> conflicts = conflictDetector.detectConflicts(base, next);
            next.setConflicts(conflicts);
            if (!conflicts.isEmpty()) {
                next.setNeedsClarification(true);
            }
        }
        return next;
    }

    /** Applies the compatibility transit fallback after preference resolution. */
    public TravelConstraints applyDerivedTransportFallback(TravelConstraints base) {
        if (base == null) return new TravelConstraints();
        TravelConstraints next = base.copy();
        if ("低".equals(next.getWalkingTolerance())
                && next.originOf("transportPreference") == ConstraintOrigin.DEFAULT) {
            next.setTransportPreference("公交优先");
            next.markOrigin("transportPreference", ConstraintOrigin.DERIVED);
        }
        return next;
    }

    private void refreshMissing(TravelConstraints constraints) {
        boolean arrival = !"未提供".equals(constraints.getArrivalAt());
        boolean departure = !"未提供".equals(constraints.getDepartureAt());
        boolean duration = constraints.originOf("durationDays") != ConstraintOrigin.DEFAULT || arrival || departure;
        List<String> missing = criticalMissing(arrival, departure, duration);
        constraints.setCriticalMissingFields(missing);
        constraints.setNeedsClarification(!missing.isEmpty());
    }

    private List<String> criticalMissing(boolean arrivalFound, boolean departureFound, boolean durationKnown) {
        List<String> missing = new ArrayList<>();
        if (!arrivalFound) missing.add("arrivalAt");
        if (!departureFound) missing.add("departureAt");
        if (!durationKnown && !arrivalFound && !departureFound) missing.add("durationDays");
        return missing;
    }

    private void setList(Map<String, Object> values, String key, Consumer<List<String>> setter,
                         TravelConstraints target, String originField) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list)) return;
        List<String> normalized = list.stream().map(String::valueOf).map(String::trim)
                .filter(item -> !item.isBlank()).distinct().toList();
        if (!normalized.isEmpty()) {
            setter.accept(new ArrayList<>(normalized));
            target.markOrigin(originField, ConstraintOrigin.REQUEST);
        }
    }

    private boolean setText(Map<String, Object> values, String key, Consumer<String> setter) {
        Object value = values.get(key);
        if (value != null && !String.valueOf(value).trim().isBlank()) {
            setter.accept(String.valueOf(value).trim());
            return true;
        }
        return false;
    }

    private List<String> extractMustVisit(String source) {
        List<String> result = new ArrayList<>();
        Matcher matcher = MUST_VISIT.matcher(source);
        while (matcher.find()) {
            String value = matcher.group(1).trim();
            if (!value.isBlank()) result.add(value.split("[和与、]")[0].trim());
        }
        return result.stream().filter(value -> !value.isBlank()).distinct().toList();
    }

    private boolean positiveKeyword(String source, String keyword) {
        if (source == null || keyword == null) return false;
        int from = 0;
        while ((from = source.indexOf(keyword, from)) >= 0) {
            if (!negatedPrefix(source, from)) return true;
            from += keyword.length();
        }
        return false;
    }

    private boolean negatedKeyword(String source, String keyword) {
        if (source == null || keyword == null) return false;
        int from = 0;
        while ((from = source.indexOf(keyword, from)) >= 0) {
            if (negatedPrefix(source, from)) return true;
            from += keyword.length();
        }
        return false;
    }

    private boolean negatedPrefix(String source, int index) {
        int start = Math.max(0, index - 8);
        return source.substring(start, index).matches(".*(?:" + NEGATION + ")\\s*$");
    }

    private int parseNumber(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { }
        if (value.length() == 1) return "一二三四五六七八九".indexOf(value) + 1;
        if (value.equals("十")) return 10;
        if (value.startsWith("十")) return 10 + parseNumber(value.substring(1));
        if (value.endsWith("十")) return parseNumber(value.substring(0, value.length() - 1)) * 10;
        if (value.contains("十")) {
            String[] parts = value.split("十", -1);
            return parseNumber(parts[0]) * 10 + (parts.length > 1 && !parts[1].isBlank() ? parseNumber(parts[1]) : 0);
        }
        return 2;
    }

    private String nullToEmpty(String value) { return value == null ? "" : value; }

    private record Keyword(String keyword, String label) { }
}
