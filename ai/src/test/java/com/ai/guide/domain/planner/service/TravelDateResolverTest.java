package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.TravelConstraints;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TravelDateResolverTest {

    @Test
    void defaultsToTodayForUndatedSpatialPrompt() {
        TravelConstraints constraints = TravelConstraints.builder()
                .startPlace("重庆交通大学")
                .timeBudgetMinutes(180)
                .rawPrompt("我在重庆交通大学 为我规划一个3小时旅游方案")
                .build();

        String resolvedDay1 = TravelDateResolver.resolveItineraryDate(constraints, 1);
        assertEquals(LocalDate.now().toString(), resolvedDay1);
        assertFalse(TravelDateResolver.hasExplicitDate(constraints));
    }

    @Test
    void resolvesExplicitIsoDate() {
        TravelConstraints constraints = TravelConstraints.builder()
                .rawPrompt("2026-10-01 去重庆玩三天")
                .build();

        assertEquals("2026-10-01", TravelDateResolver.resolveItineraryDate(constraints, 1));
        assertEquals("2026-10-02", TravelDateResolver.resolveItineraryDate(constraints, 2));
        assertEquals("2026-10-03", TravelDateResolver.resolveItineraryDate(constraints, 3));
        assertTrue(TravelDateResolver.hasExplicitDate(constraints));
    }

    @Test
    void resolvesRelativeKeywords() {
        TravelConstraints todayC = TravelConstraints.builder().rawPrompt("今天下午出发").build();
        assertEquals(LocalDate.now().toString(), TravelDateResolver.resolveItineraryDate(todayC, 1));
        assertTrue(TravelDateResolver.hasExplicitDate(todayC));

        TravelConstraints tomorrowC = TravelConstraints.builder().rawPrompt("明天上午到重庆玩一天").build();
        assertEquals(LocalDate.now().plusDays(1).toString(), TravelDateResolver.resolveItineraryDate(tomorrowC, 1));
        assertTrue(TravelDateResolver.hasExplicitDate(tomorrowC));

        TravelConstraints dayAfterTomorrowC = TravelConstraints.builder().rawPrompt("后天去洪崖洞").build();
        assertEquals(LocalDate.now().plusDays(2).toString(), TravelDateResolver.resolveItineraryDate(dayAfterTomorrowC, 1));
        assertTrue(TravelDateResolver.hasExplicitDate(dayAfterTomorrowC));
    }

    @Test
    void resolvesChineseMonthDay() {
        int year = LocalDate.now().getYear();
        TravelConstraints constraints = TravelConstraints.builder().rawPrompt("10月15日去重庆").build();
        assertEquals(String.format("%04d-10-15", year), TravelDateResolver.resolveItineraryDate(constraints, 1));
        assertTrue(TravelDateResolver.hasExplicitDate(constraints));
    }

    @Test
    void resolvesWeekday() {
        TravelConstraints constraints = TravelConstraints.builder().rawPrompt("周五到重庆").build();
        String expected = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).toString();
        assertEquals(expected, TravelDateResolver.resolveItineraryDate(constraints, 1));
        assertTrue(TravelDateResolver.hasExplicitDate(constraints));
    }
}
