package com.ai.guide.domain.planner.service.strategy;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
import com.ai.guide.domain.planner.model.topography.BarrierCrossingType;
import com.ai.guide.domain.planner.model.topography.ElevationFlow;
import com.ai.guide.domain.planner.model.topography.ElevationTier;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.domain.taxonomy.gateway.PlannerTaxonomyPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class XiAnHeritageTopographyStrategy implements TopographyStrategyPort {

    private final PlannerTaxonomyPort taxonomy;

    @Autowired
    public XiAnHeritageTopographyStrategy(PlannerTaxonomyPort taxonomy) {
        this.taxonomy = taxonomy == null ? TaxonomyPort.empty() : taxonomy;
    }

    public XiAnHeritageTopographyStrategy() { this(TaxonomyPort.empty()); }

    @Override
    public String supportedCity() {
        return "XIAN";
    }

    @Override
    public ElevationTier resolveElevationTier(Attraction attraction) {
        if (attraction == null) return ElevationTier.STANDARD_PLAIN;
        String name = attraction.getName() != null ? attraction.getName() : "";
        String id = attraction.getId() == null ? "" : attraction.getId();
        if (matches(name, "xianTopoHighNameTokens") || matches(id, "xianTopoHighIdTokens")) {
            return ElevationTier.HIGH_ALTITUDE;
        }
        return ElevationTier.STANDARD_PLAIN;
    }

    @Override
    public ElevationTier resolveElevationTier(String id, String name, String district, String location) {
        if (matches(name, "xianTopoHighNameTokens") || matches(id, "xianTopoHighIdTokens")) {
            return ElevationTier.HIGH_ALTITUDE;
        }
        return ElevationTier.STANDARD_PLAIN;
    }

    @Override
    public BarrierCrossingType detectBarrierCrossing(Attraction from, Attraction to) {
        if (from == null || to == null) return BarrierCrossingType.NONE;
        boolean fromInside = isInsideCityWall(from);
        boolean toInside = isInsideCityWall(to);
        if (fromInside != toInside) {
            return BarrierCrossingType.HISTORICAL_WALL;
        }
        return BarrierCrossingType.NONE;
    }

    private boolean isInsideCityWall(Attraction a) {
        String name = a.getName() != null ? a.getName() : "";
        String dist = a.getDistrict() != null ? a.getDistrict() : "";
        return matches(dist, "xianTopoInnerDistrictTokens") && matches(name, "xianTopoInnerNameTokens");
    }

    @Override
    public ElevationFlow detectElevationFlow(Attraction from, Attraction to) {
        return ElevationFlow.FLAT;
    }

    @Override
    public double calculateZigZagPenalty(List<Attraction> historyStopsInDay, Attraction candidate) {
        if (historyStopsInDay == null || historyStopsInDay.size() < 2 || candidate == null) return 0.0;
        int size = historyStopsInDay.size();
        Attraction last = historyStopsInDay.get(size - 1);
        Attraction secondLast = historyStopsInDay.get(size - 2);

        boolean sOutskirts = isOutskirts(secondLast);
        boolean lOutskirts = isOutskirts(last);
        boolean cOutskirts = isOutskirts(candidate);
        if ((sOutskirts && !lOutskirts && cOutskirts) || (!sOutskirts && lOutskirts && !cOutskirts)) {
            return penalty("xianTopoZigzagPenalty");
        }
        return 0.0;
    }

    private boolean isOutskirts(Attraction a) {
        if (a == null) return false;
        String name = a.getName() != null ? a.getName() : "";
        String dist = a.getDistrict() != null ? a.getDistrict() : "";
        return matches(dist, "xianTopoOutskirtDistrictTokens") || matches(name, "xianTopoOutskirtNameTokens");
    }

    @Override
    public String generateTransitCharacteristicHint(Attraction from, Attraction to, String transportPreference) {
        if (from == null || to == null) return "";
        if (from == null || to == null) return "";
        // Live route and transit facts must come from the configured route provider.
        return "";
    }

    private boolean matches(String value, String key) {
        return value != null && taxonomy.getHints(key).stream().anyMatch(value::contains);
    }

    private double penalty(String key) {
        try { return taxonomy.getHints(key).stream().findFirst().map(Double::parseDouble).orElse(0.0); }
        catch (RuntimeException invalidConfig) { return 0.0; }
    }

    @Override
    public boolean allowStartPlaceProximity(Attraction previous, Attraction candidate) {
        return true;
    }
}
