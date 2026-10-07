package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.gateway.TaxonomyPort;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Converts external time-part policy into an explicit, bounded execution scope. */
public final class AdjustmentTimeScopeResolver {
    private AdjustmentTimeScopeResolver() {}
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)(\\d{1,2})[:：](\\d{2})(?!\\d)");
    public static void resolve(PlanAdjustmentIntent intent, TaxonomyPort taxonomy) {
        if (intent == null) return;
        String raw = intent.getRawMessage() == null ? "" : intent.getRawMessage();
        var preserves = new ArrayList<>(intent.getPreserveReferences() == null ? List.<String>of() : intent.getPreserveReferences());
        for (String clause : IntentLanguagePolicy.preservedReferences(raw)) if (!preserves.contains(clause)) preserves.add(clause);
        intent.setPreserveReferences(preserves);
        if (intent.isTimeScopeResolved()) { validate(intent); return; }
        if (intent.getType() == ConversationIntentType.PLACE_QUESTION || intent.getType() == ConversationIntentType.APPLY_REPLACEMENT) { validate(intent); return; }
        String text = IntentLanguagePolicy.positiveText(raw);
        // A compound request has several execution scopes. Only its shared
        // clauses define a common window; each action is resolved when selected.
        if (intent.getMissingFields().contains("COMPOUND_ACTION_ORDER")) {
            text = IntentLanguagePolicy.positiveText(String.join("，", IntentLanguagePolicy.clauses(raw).stream()
                    .filter(clause -> !intent.getCompoundActions().contains(clause)).toList()));
        }
        var clock = CLOCK.matcher(text); var times = new ArrayList<Integer>();
        while (clock.find()) {
            int hour = Integer.parseInt(clock.group(1)), minute = Integer.parseInt(clock.group(2));
            if (hour > 24 || minute > 59 || (hour == 24 && minute != 0)) { clarify(intent); return; }
            times.add(hour * 60 + minute);
        }
        if (times.size() == 2 && text.matches("(?s).*(?:至|到|[-–—~～]).*")) {
            intent.setTargetStartMinute(times.get(0)); intent.setTargetEndMinute(times.get(1));
        } else if (times.size() == 1 && text.matches("(?s).*(?:以后|之后|后|起).*")) {
            intent.setTargetStartMinute(times.get(0)); intent.setTargetEndMinute(1440);
        } else if (times.size() == 1 && text.matches("(?s).*(?:以前|之前|前).*")) {
            intent.setTargetStartMinute(0); intent.setTargetEndMinute(times.get(0));
        } else if (!times.isEmpty()) { clarify(intent); return; }
        if (!times.isEmpty()) { validate(intent); return; }
        String windowText = text;
        var windows = (taxonomy == null ? List.<TaxonomyPort.AdjustmentWindow>of() : taxonomy.getAdjustmentWindows()).stream()
                .filter(w -> w.aliases().stream().anyMatch(windowText::contains))
                .sorted(Comparator.comparingInt(TaxonomyPort.AdjustmentWindow::startMinute)).toList();
        if (windows.isEmpty()) { validate(intent); return; }
        int start = windows.get(0).startMinute(), end = windows.get(0).endMinute();
        for (var window : windows) {
            if (window.startMinute() > end) {
                clarify(intent);
                return;
            }
            end = Math.max(end, window.endMinute());
        }
        intent.setTargetStartMinute(start);
        intent.setTargetEndMinute(end);
        validate(intent);
    }
    private static void validate(PlanAdjustmentIntent i) {
        if ((i.getTargetStartMinute() == null) != (i.getTargetEndMinute() == null)
                || i.getTargetStartMinute() != null && (i.getTargetStartMinute() < 0 || i.getTargetEndMinute() > 1440 || i.getTargetStartMinute() >= i.getTargetEndMinute())) clarify(i);
    }
    private static void clarify(PlanAdjustmentIntent i) {
        i.setRequiresClarification(true);
        var missing = new ArrayList<>(i.getMissingFields());
        missing.remove("TIME_RANGE"); missing.add(0, "TIME_RANGE"); i.setMissingFields(missing);
        i.setClarificationQuestion("请明确一个有效的调整时段，例如18:00以后或20:00至21:00；其余安排会保留。");
    }
    public static int minute(Map<String,Object> stop) {
        var m = CLOCK.matcher(String.valueOf(stop.getOrDefault("time", stop.getOrDefault("startTime", ""))));
        return m.matches() ? Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) : -1;
    }
    public static boolean preserves(Map<String,Object> stop, PlanAdjustmentIntent intent, TaxonomyPort taxonomy) {
        String name = String.valueOf(stop.getOrDefault("name", "")); int minute = minute(stop);
        for (String ref : intent.getPreserveReferences() == null ? List.<String>of() : intent.getPreserveReferences()) {
            if (!name.isBlank() && ref.contains(name)) return true;
            var clocks = CLOCK.matcher(ref); var values = new ArrayList<Integer>();
            while (clocks.find()) values.add(Integer.parseInt(clocks.group(1))*60 + Integer.parseInt(clocks.group(2)));
            if (values.size() == 2 && minute >= values.get(0) && minute < values.get(1)) return true;
            if (values.size() == 1 && ((ref.matches("(?s).*(?:以前|之前|前).*" ) && minute < values.get(0)) || (ref.matches("(?s).*(?:以后|之后|后).*" ) && minute >= values.get(0)))) return true;
            for (var w : taxonomy.getAdjustmentWindows()) if (w.aliases().stream().anyMatch(ref::contains) && minute >= w.startMinute() && minute < w.endMinute()) return true;
        }
        return false;
    }
}
