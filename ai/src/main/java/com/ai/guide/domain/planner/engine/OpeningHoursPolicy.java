package com.ai.guide.domain.planner.engine;

import java.time.LocalDate;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;

/** Uses supplied opening evidence; a venue category alone is not an opening schedule. */
public final class OpeningHoursPolicy {
    private OpeningHoursPolicy() {}
    private static final Pattern INTERVAL = Pattern.compile("(\\d{1,2})[:：](\\d{2})\\s*[-–—~～至到]\\s*(\\d{1,2})[:：](\\d{2})");
    private static final Pattern CLOSED = Pattern.compile("(?:每)?(?:周|星期|礼拜)([一二三四五六日天])\\s*(?:闭馆|闭园|休息|不开放|停业)");

    public static String evidence(String openingHours, String bestTime) {
        if (openingHours != null && !openingHours.isBlank()) return openingHours;
        return bestTime != null && bestTime.matches("(?s).*(?:营业|开放|闭馆|闭园|休息|停业).*" ) ? bestTime : "";
    }

    public static boolean isTimeOpen(String evidence, String time) {
        if (time == null || time.isBlank()) return true;
        Integer parsed = new ItineraryTimeEngine().parseExplicitTimeMinutes(time);
        if (parsed == null) return true;
        int minute = Math.floorMod(parsed, 1440);
        var intervals = INTERVAL.matcher(evidence == null ? "" : evidence);
        boolean known = false;
        while (intervals.find()) {
            int from = Integer.parseInt(intervals.group(1)) * 60 + Integer.parseInt(intervals.group(2));
            int to = Integer.parseInt(intervals.group(3)) * 60 + Integer.parseInt(intervals.group(4));
            if (from >= 1440 || to > 1440 || Integer.parseInt(intervals.group(2)) > 59 || Integer.parseInt(intervals.group(4)) > 59) continue;
            known = true;
            if (from < to ? minute >= from && minute < to : minute >= from || minute < to) return true;
        }
        return !known;
    }

    /** The entire occupied interval must fit the supplied schedule, including lunch breaks and midnight. */
    public static boolean isVisitOpen(String evidence, String date, String time, int durationMinutes) {
        var engine = new ItineraryTimeEngine();
        Integer start = engine.parseExplicitTimeMinutes(time);
        if (start == null) return !isClosed(evidence, date, time);
        List<int[]> intervals = new ArrayList<>();
        var matcher = INTERVAL.matcher(evidence == null ? "" : evidence);
        while (matcher.find()) {
            int from = Integer.parseInt(matcher.group(1)) * 60 + Integer.parseInt(matcher.group(2));
            int to = Integer.parseInt(matcher.group(3)) * 60 + Integer.parseInt(matcher.group(4));
            if (from >= 1440 || to > 1440 || Integer.parseInt(matcher.group(2)) > 59 || Integer.parseInt(matcher.group(4)) > 59) continue;
            intervals.add(new int[]{from, to > from ? to : to + 1440});
        }
        long end = (long) start + Math.max(0, durationMinutes);
        // Weekly closure evidence repeats; inspecting eight dates bounds very long input.
        long closureEnd = Math.min(Math.max((long) start + 1, end), (long) start + 8 * 1440);
        for (long cursor = start; cursor < closureEnd; ) {
            if (isClosed(evidence, date, engine.formatTime((int) cursor))) return false;
            cursor = Math.min(closureEnd, (Math.floorDiv(cursor, 1440) + 1) * 1440);
        }
        if (intervals.isEmpty()) return true;
        if (!isTimeOpen(evidence, time)) return false;
        long cursor = start;
        // Daily intervals repeat; two full days suffice to prove continuous coverage.
        long coverageEnd = Math.min(end, (long) start + 2 * 1440);
        while (cursor < coverageEnd) {
            long day = Math.floorDiv(cursor, 1440);
            long coveredUntil = cursor;
            for (long offset = day - 1; offset <= day; offset++) {
                for (int[] interval : intervals) {
                    long from = offset * 1440 + interval[0];
                    long to = offset * 1440 + interval[1];
                    if (from <= cursor && to > coveredUntil) coveredUntil = to;
                }
            }
            if (coveredUntil == cursor) return false;
            cursor = coveredUntil;
        }
        return true;
    }

    public static boolean isClosed(String evidence, String date, String time) {
        int weekday;
        Integer parsedTime = new ItineraryTimeEngine().parseExplicitTimeMinutes(time);
        int offset = parsedTime == null ? 0 : parsedTime / 1440;
        try {
            weekday = LocalDate.parse(date).plusDays(offset).getDayOfWeek().getValue();
        } catch (Exception unknownDate) {
            weekday = 0;
            String[] days = {"一", "二", "三", "四", "五", "六", "日"};
            for (int i = 0; i < days.length; i++) if (date != null && (date.contains("周" + days[i]) || date.contains("星期" + days[i]))) weekday = i + 1;
            if (date != null && (date.contains("周天") || date.contains("星期天"))) weekday = 7;
            if (date != null && date.contains("Monday")) weekday = 1;
            if (weekday > 0) weekday = Math.floorMod(weekday - 1 + offset, 7) + 1;
        }
        var closed = CLOSED.matcher(evidence == null ? "" : evidence);
        while (closed.find()) {
            int closedDay = "一二三四五六日".indexOf(closed.group(1)) + 1;
            if ("天".equals(closed.group(1))) closedDay = 7;
            if (weekday == closedDay) return true;
        }
        return false;
    }
}
