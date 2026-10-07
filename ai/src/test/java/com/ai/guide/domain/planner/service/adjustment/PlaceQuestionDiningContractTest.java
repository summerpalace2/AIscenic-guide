package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaceQuestionDiningContractTest {
    private Map<String,Object> trip() {
        var stop = Map.<String,Object>of("id","museum", "name","蒙民伟理科馆", "type","SCENIC",
                "location","116.32,39.99", "district","海淀区", "time","10:00", "summary","科研建筑介绍");
        return Map.of("constraints",Map.of("destination","北京市"),
                "days",List.of(Map.of("day",1,"stops",List.of(stop))));
    }
    @Test void selectedCulturalBuildingIsNotDiningMerelyBecauseNameEndsInGuan() {
        var dining = mock(DiningVenueService.class);
        var strategy = new PlaceQuestionAdjustmentStrategy(mock(AttractionService.class));
        strategy.setDiningVenueService(dining);
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.PLACE_QUESTION)
                .targetStopId("museum").rawMessage("这个建筑有什么特色？").build();
        assertTrue(strategy.answerPlaceQuestion(intent,trip()).contains("科研建筑介绍"));
        verifyNoInteractions(dining);
    }
    @Test void structuredUnlistedCuisineAndSlotArePassedWithoutFoodRootMatching() {
        var dining = mock(DiningVenueService.class);
        when(dining.searchLiveDiningOrTea("海淀区","116.32,39.99","驴肉火烧","DINNER",3,"北京市"))
                .thenReturn(List.of(DiningVenue.builder().name("测试餐厅").district("海淀区").build()));
        var strategy = new PlaceQuestionAdjustmentStrategy(mock(AttractionService.class));
        strategy.setDiningVenueService(dining);
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.PLACE_QUESTION)
                .targetStopId("museum").cuisineOrPlace("驴肉火烧").genericDiningCraving(true)
                .timeSlot("DINNER").rawMessage("早些讨论午饭之后的晚餐安排").build();
        assertTrue(strategy.answerPlaceQuestion(intent,trip()).contains("测试餐厅"));
        verify(dining).searchLiveDiningOrTea("海淀区","116.32,39.99","驴肉火烧","DINNER",3,"北京市");
    }
    @Test void explicitAreaOverridesUnrelatedSelectedStopWithoutInventingCoordinates() {
        var dining = mock(DiningVenueService.class);
        var strategy = new PlaceQuestionAdjustmentStrategy(mock(AttractionService.class));
        strategy.setDiningVenueService(dining);
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.PLACE_QUESTION)
                .targetStopId("museum").targetPlace("朝阳区").cuisineOrPlace("螺蛳粉")
                .timeSlot("LUNCH").rawMessage("朝阳区附近吃点东西").build();
        assertTrue(strategy.answerPlaceQuestion(intent,trip()).contains("未找到可核验"));
        verify(dining).searchLiveDiningOrTea("朝阳区",null,"螺蛳粉","LUNCH",3,"北京市");
    }
    @Test void legacyLocationPhraseRemainsAQueryRatherThanAnAdministrativeWhitelist() {
        var dining = mock(DiningVenueService.class);
        var strategy = new PlaceQuestionAdjustmentStrategy(mock(AttractionService.class));
        strategy.setDiningVenueService(dining);
        var intent = PlanAdjustmentIntent.builder().type(ConversationIntentType.PLACE_QUESTION)
                .rawMessage("未收录新城区附近有没有茶馆").build();
        strategy.answerPlaceQuestion(intent,trip());
        verify(dining).searchLiveDiningOrTea("未收录新城区",null,"特色老茶馆与盖碗茶歇","TEA_BREAK",3,"北京市");
    }
}
