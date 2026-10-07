package com.ai.guide.domain.planner.engine;

import com.ai.guide.domain.planner.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.planner.model.OutcomeStatus;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves a search anchor from itinerary evidence, then the destination's live POI data. */
@Component
public class DynamicGeoCentroidResolver {
    private final GeoRouteGatewayPort gateway;

    public DynamicGeoCentroidResolver(@Autowired(required = false) GeoRouteGatewayPort gateway) {
        this.gateway = gateway;
    }

    public String resolve(Map<String, Object> trip, TravelConstraints constraints, List<Map<String, Object>> stops) {
        String coordinate = PlanAdjustmentHelper.resolveDefaultCoordinate(trip, constraints, stops);
        return coordinate != null ? coordinate : resolveCityCenter(PlanAdjustmentHelper.resolveTripCity(trip, constraints));
    }

    public String resolveCityCenter(String city) {
        if (city == null || city.isBlank() || gateway == null || !gateway.isConfigured()) return null;
        try {
            String query = city.endsWith("市") ? city + "政府" : city + "市政府";
            var outcome = gateway.searchPoi(query, city);
            if (outcome == null || outcome.status() != OutcomeStatus.SUCCESS || outcome.candidates() == null) return null;
            for (var candidate : outcome.candidates()) {
                if (candidate == null || candidate.coordinate() == null) continue;
                String coordinate = String.format(Locale.ROOT, "%.6f,%.6f",
                        candidate.coordinate().longitude(), candidate.coordinate().latitude());
                if (PlanAdjustmentHelper.isValidCoordinate(coordinate)) return coordinate;
            }
        } catch (RuntimeException ignored) {
            // No location is more accurate than a fabricated coordinate when the provider is unavailable.
        }
        return null;
    }
}
