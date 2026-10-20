package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.service.GeoCityCenterResolver;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves a search anchor from itinerary evidence, then the destination's live POI data. */
@Component
public class DynamicGeoCentroidResolver {
    private final GeoRouteGatewayPort gateway;
    private final double maxCentroidSpanMeters;

    @Autowired
    public DynamicGeoCentroidResolver(@Autowired(required = false) GeoRouteGatewayPort gateway,
                                      @Value("${planner.geo-centroid.max-span-meters}") double maxCentroidSpanMeters) {
        this.gateway = gateway;
        this.maxCentroidSpanMeters = maxCentroidSpanMeters;
    }

    public DynamicGeoCentroidResolver(GeoRouteGatewayPort gateway) {
        this(gateway, 50_000.0);
    }

    public String resolve(Map<String, Object> trip, TravelConstraints constraints, List<Map<String, Object>> stops) {
        String coordinate = PlanAdjustmentHelper.resolveDefaultCoordinate(trip, constraints, stops, maxCentroidSpanMeters);
        return coordinate != null ? coordinate : resolveCityCenter(PlanAdjustmentHelper.resolveTripCity(trip, constraints));
    }

    public String resolveCityCenter(String city) {
        return new GeoCityCenterResolver(gateway).resolve(city);
    }
}
