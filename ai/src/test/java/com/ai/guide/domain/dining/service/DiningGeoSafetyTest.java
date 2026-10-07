package com.ai.guide.domain.dining.service;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.planner.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.model.*;
import com.ai.guide.domain.rag.pipeline.AmapResponseNormalizer.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class DiningGeoSafetyTest {
    private PoiOutcome result(PoiCandidate... candidates) {
        return new PoiOutcome(OutcomeStatus.SUCCESS,List.of(candidates),"test fixture",Instant.now());
    }
    private PoiCandidate poi(String name,double lng,double lat,String typecode) {
        return new PoiCandidate("mock-poi",name,new Coordinate(lng,lat),"测试地址","餐饮服务",null,null,null,null,null,null,null,typecode,null);
    }
    @Test void noLocationOrGatewayNeverInventsChongqingRestaurant() {
        var service=new DiningVenueService();
        var constraints=new TravelConstraints(); constraints.setDestination("北京市");
        var option=service.resolveNearbyDining(null,"LUNCH",constraints);
        assertEquals("dining-fallback-default",option.id());
        assertNull(option.location());
        assertFalse(option.name().contains("重庆"));
        assertEquals("距离待确认",option.distanceFromAttraction());
    }
    @Test void coldStartResolvesDestinationAndPreservesExplicitCuisine() {
        var gateway=mock(GeoRouteGatewayPort.class);when(gateway.isConfigured()).thenReturn(true);
        when(gateway.searchPoi("北京市政府","北京市")).thenReturn(result(poi("北京市政府",116.4,39.9,"130100")));
        when(gateway.searchAround(anyString(),anyInt(),anyString(),eq("驴肉火烧"),eq("北京市")))
                .thenReturn(result(poi("测试餐厅",116.4001,39.9001,"050100")));
        var service=new DiningVenueService(null,gateway,new ObjectMapper());
        var constraints=new TravelConstraints();constraints.setDestination("北京市");constraints.setDietPreference("驴肉火烧");
        var option=service.resolveNearbyDining(null,"LUNCH",constraints);
        verify(gateway).searchPoi("北京市政府","北京市");
        verify(gateway).searchAround(anyString(),anyInt(),anyString(),eq("驴肉火烧"),eq("北京市"));
        assertTrue(option.name().contains("测试餐厅"));
        assertEquals("人均消费待确认",option.averageCost());
        assertEquals("菜单与招牌菜待商户确认",option.specialtyDish());
        assertFalse(option.summary().contains("高分"));
    }
    @Test void specifiedCityIsPassedIntoTextSearchWithoutCoordinates() {
        var gateway=mock(GeoRouteGatewayPort.class);when(gateway.isConfigured()).thenReturn(true);
        when(gateway.searchPoi(eq("海淀区 火锅"),eq("北京市"),anyString()))
                .thenReturn(result(poi("校内火锅餐厅",116.32,39.99,"050100")));
        var service=new DiningVenueService(null,gateway,new ObjectMapper());
        var venues=service.searchLiveDiningOrTea("海淀区",null,"火锅","LUNCH",1,"北京市");
        assertEquals(1,venues.size());
        verify(gateway).searchPoi(eq("海淀区 火锅"),eq("北京市"),anyString());
    }
    @Test void providerEducationPoiCannotMasqueradeAsDiningDespiteFoodName() {
        var gateway=mock(GeoRouteGatewayPort.class);when(gateway.isConfigured()).thenReturn(true);
        when(gateway.searchAround(anyString(),anyInt(),anyString(),anyString(),anyString()))
                .thenReturn(result(poi("火锅实验楼",116.32,39.99,"141201")));
        var service=new DiningVenueService(null,gateway,new ObjectMapper());
        var anchor=Attraction.builder().id("amap-test").location("116.32,39.99").district("海淀区").build();
        var option=service.resolveNearbyDining(anchor,"LUNCH",null);
        assertEquals("dining-fallback-default",option.id());
    }
    @Test void missingCoordinateCannotSelectAnUnrelatedSeedCity() {
        var service=new DiningVenueService();
        assertTrue(service.searchLiveDiningOrTea("海淀区",null,"早餐","BREAKFAST",3,"北京市").isEmpty());
        assertTrue(Double.isInfinite(DiningVenueService.distanceKm("NaN,NaN","116.4,39.9")));
    }
    @Test void failedExplicitCuisineSearchNeverWidensToUnrelatedFood() {
        var gateway=mock(GeoRouteGatewayPort.class);when(gateway.isConfigured()).thenReturn(true);
        when(gateway.searchAround(anyString(),anyInt(),anyString(),anyString(),anyString()))
                .thenReturn(result());
        var service=new DiningVenueService(null,gateway,new ObjectMapper());
        var constraints=new TravelConstraints();constraints.setDestination("北京");constraints.setDietPreference("驴肉火烧");
        var anchor=Attraction.builder().id("test").location("116.32,39.99").district("海淀区").build();
        assertEquals("dining-fallback-default",service.resolveNearbyDining(anchor,"LUNCH",constraints).id());
        verify(gateway,times(3)).searchAround(anyString(),anyInt(),anyString(),eq("驴肉火烧"),eq("北京"));
    }
    @Test void locallyClosestSeedCannotSubstituteAnUnsupportedExplicitCuisine() {
        var service=new DiningVenueService();
        var anchor=Attraction.builder().id("test").location("106.577054,29.557161").district("渝中区").build();
        var constraints=new TravelConstraints();constraints.setDietPreference("驴肉火烧");
        assertEquals("dining-fallback-default",service.resolveNearbyDining(anchor,"LUNCH",constraints).id());
    }
    @Test void canonicalFlavorAliasMatchesLocalDessertButSpecificDishRemainsExact() {
        var service=new DiningVenueService();
        var anchor=Attraction.builder().id("test").location("106.577054,29.557161").district("渝中区").build();
        var options=service.resolveDiningReplacementCandidates("渝中区", "", "特色甜品糖水", "TEA_BREAK", anchor, null);
        assertFalse(options.isEmpty());
        assertTrue(service.searchLiveDiningOrTea("渝中区",anchor.getLocation(),"龙眼芋泥糖水","TEA_BREAK",3,"重庆市").isEmpty());
    }
    @Test void dessertSearchRejectsGeneralRestaurantAndTrustsProviderCategoryForUnfamiliarName() {
        var gateway=mock(GeoRouteGatewayPort.class); when(gateway.isConfigured()).thenReturn(true);
        var restaurant=new PoiCandidate("restaurant","普通家常菜馆",new Coordinate(116.3201,39.9901),
                "测试地址","餐饮服务;中餐厅",null,null,null,null,null,null,null,"050100",null);
        var dessert=new PoiCandidate("dessert","云朵",new Coordinate(116.3202,39.9902),
                "测试地址","餐饮服务;糕点店",null,null,null,null,null,null,null,null,null);
        when(gateway.searchAround(anyString(),anyInt(),anyString(),anyString(),anyString()))
                .thenReturn(result(restaurant,dessert));
        var service=new DiningVenueService(null,gateway,new ObjectMapper()) {
            @Override public List<DiningVenue> listAll() { return List.of(); }
        };
        var venues=service.searchLiveDiningOrTea("海淀区","116.32,39.99","特色甜品糖水","TEA_BREAK",3,"北京市");
        assertEquals(List.of("云朵"),venues.stream().map(DiningVenue::getName).toList());
        verify(gateway).searchAround(anyString(),anyInt(),eq("甜品店|糕点店|冷饮店"),eq("甜品"),eq("北京市"));
    }

    @Test void dessertSearchWithOnlyUnrelatedRestaurantsReturnsNoCandidates() {
        var gateway=mock(GeoRouteGatewayPort.class); when(gateway.isConfigured()).thenReturn(true);
        when(gateway.searchAround(anyString(),anyInt(),anyString(),anyString(),anyString()))
                .thenReturn(result(new PoiCandidate("restaurant","普通家常菜馆",new Coordinate(116.3201,39.9901),
                        "测试地址","餐饮服务;中餐厅",null,null,null,null,null,null,null,"050100",null)));
        when(gateway.searchPoi(anyString(),anyString(),anyString())).thenReturn(result());
        var service=new DiningVenueService(null,gateway,new ObjectMapper()) {
            @Override public List<DiningVenue> listAll() { return List.of(); }
        };
        assertTrue(service.searchLiveDiningOrTea("海淀区","116.32,39.99","特色甜品糖水","TEA_BREAK",3,"北京市").isEmpty());
    }

    @Test void replacingTheOnlyMatchingRestaurantCannotReturnTheSameRestaurant() {
        var venue=DiningVenue.builder().id("fixture-venue").name("唯一螺蛳粉店")
                .category("螺蛳粉").district("测试区").location("116.4,39.9")
                .mealTypes(List.of("LUNCH")).build();
        var service=new DiningVenueService() {
            @Override public List<DiningVenue> listAll() { return List.of(venue); }
        };
        var anchor=Attraction.builder().location("116.4,39.9").district("测试区").build();
        assertTrue(service.resolveDiningReplacementCandidates("测试区","唯一螺蛳粉店","螺蛳粉","LUNCH",anchor,null).isEmpty());
    }
}
