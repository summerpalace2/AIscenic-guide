package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

/** Calendar references resolve only against the authoritative dated trip, never the server clock. */
public final class PlannerCalendarDateResolver {
    private PlannerCalendarDateResolver() { }
    private static final Pattern ISO = Pattern.compile("(?<!\\d)(\\d{4}-\\d{1,2}-\\d{1,2})(?!\\d)");
    private static final Pattern MONTH_DAY = Pattern.compile("(?:(\\d{4})年)?([一二三四五六七八九十两\\d]+)月([一二三四五六七八九十两\\d]+)[日号]");
    public record Reference(boolean present, Integer day, String literal, boolean dateOnly, String error) { }
    public static Reference resolve(String query, Map<String,Object> trip) {
        String raw = query == null ? "" : query;
        var literals = new ArrayList<String>();
        var requested = new ArrayList<java.util.function.Predicate<LocalDate>>();
        var iso = ISO.matcher(raw);
        while (iso.find()) {
            String literal = iso.group(1); literals.add(literal);
            try {
                String[] parts=literal.split("-");
                LocalDate date=LocalDate.of(Integer.parseInt(parts[0]),Integer.parseInt(parts[1]),Integer.parseInt(parts[2]));
                requested.add(date::equals);
            } catch (RuntimeException invalid) { return new Reference(true,null,literal,false,"日期无效"); }
        }
        var monthDay=MONTH_DAY.matcher(raw);
        while (monthDay.find()) {
            String literal=monthDay.group(); literals.add(literal);
            try {
                MonthDay date=MonthDay.of(Objects.requireNonNull(ConversationIntentClassifier.parseChineseNumber(monthDay.group(2))),
                        Objects.requireNonNull(ConversationIntentClassifier.parseChineseNumber(monthDay.group(3))));
                Integer year=monthDay.group(1)==null?null:Integer.valueOf(monthDay.group(1));
                requested.add(day -> MonthDay.from(day).equals(date) && (year==null || day.getYear()==year));
            } catch (RuntimeException invalid) { return new Reference(true,null,literal,false,"日期无效"); }
        }
        if (literals.isEmpty()) return new Reference(false,null,"",false,"");
        if (literals.size()!=1) return new Reference(true,null,String.join("、",literals),false,"提到了多个日期，请只指定本次要调整的日期");
        String literal=literals.getFirst();
        var matches=new LinkedHashSet<Integer>();
        if (trip!=null && trip.get("days") instanceof List<?> days)
            for (Object value:days) if (value instanceof Map<?,?> day) {
                try {
                    LocalDate actual=LocalDate.parse(String.valueOf(day.get("date")));
                    if (requested.getFirst().test(actual) && day.get("day") instanceof Number number) matches.add(number.intValue());
                } catch (RuntimeException ignored) { /* Undated legacy days cannot establish calendar identity. */ }
            }
        String residue=raw.replace(literal,"").replaceAll("[，,。！!\\s]","");
        // Calendar corrections can contain conversational filler. Only a new modifying
        // command prevents date-only continuation; preview/confirmation wording is not a command.
        boolean dateOnly=!com.ai.guide.domain.language.IntentLanguagePolicy.hasAdjustmentAction(residue);
        return new Reference(true,matches.size()==1?matches.iterator().next():null,literal,dateOnly,
                matches.isEmpty()?"该日期不在当前行程的已知日期内":matches.size()>1?"该月日在行程中不唯一，请提供完整年月日":"");
    }
    public static void apply(PlanAdjustmentIntent intent,Reference reference) {
        if (intent==null || reference==null || !reference.present()) return;
        intent.setTargetDayReference(reference.literal());
        if (reference.day()==null) {
            intent.setDayNumber(null); intent.setType(ConversationIntentType.CLARIFICATION);
            intent.setRequiresClarification(true);
            var missing=new ArrayList<>(intent.getMissingFields()); missing.remove("DAY_NUMBER"); missing.add(0,"DAY_NUMBER");
            intent.setMissingFields(missing); intent.setClarificationQuestion(reference.error()+"。请指定当前行程中的日期或第几天。");
            return;
        }
        if (!Objects.equals(intent.getDayNumber(),reference.day())) intent.setTargetStopId(null);
        intent.setDayNumber(reference.day());
        var missing=new ArrayList<>(intent.getMissingFields()); missing.remove("DAY_NUMBER"); intent.setMissingFields(missing);
        if (missing.isEmpty() && intent.isRequiresClarification() && !"UNKNOWN".equals(intent.getOperation())) {
            intent.setRequiresClarification(false); intent.setClarificationQuestion(null);
            intent.setType(switch (intent.getOperation()) {
                case "ADD_STOP" -> ConversationIntentType.ADD_STOP;
                case "REMOVE_STOP" -> ConversationIntentType.REMOVE_STOP;
                case "REPLACE_STOP","SUGGEST_REPLACEMENTS" -> ConversationIntentType.SUGGEST_REPLACEMENTS;
                case "REPLAN_DAY" -> ConversationIntentType.REPLAN_DAY;
                case "REDUCE_DENSITY","REDUCE_DAY_DENSITY" -> ConversationIntentType.REDUCE_DAY_DENSITY;
                default -> intent.getType();
            });
        }
    }
}
