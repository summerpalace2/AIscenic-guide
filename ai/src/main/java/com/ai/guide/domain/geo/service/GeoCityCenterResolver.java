package com.ai.guide.domain.geo.service;

import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;

import java.util.Locale;
import java.util.Objects;

/** Resolves a city search anchor from live geographic provider evidence. */
public final class GeoCityCenterResolver {
    private final GeoRouteGatewayPort gateway;

    public GeoCityCenterResolver(GeoRouteGatewayPort gateway) {
        this.gateway = gateway;
    }

    public String resolve(String city) {
        if (city == null || city.isBlank() || gateway == null || !gateway.isConfigured()) return null;
        try {
            String query = city.endsWith("市") ? city + "政府" : city + "市政府";
            var outcome = gateway.searchPoi(query, city);
            if (outcome == null || outcome.status() != GeoOutcomeStatus.SUCCESS || outcome.candidates() == null) return null;
            return outcome.candidates().stream().filter(Objects::nonNull)
                    .filter(candidate -> candidate.coordinate() != null && candidate.coordinate().isValid())
                    .map(candidate -> String.format(Locale.ROOT, "%.6f,%.6f",
                            candidate.coordinate().longitude(), candidate.coordinate().latitude()))
                    .findFirst().orElse(null);
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
