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
public class ChengduPlainTopographyStrategy implements TopographyStrategyPort {

    private final PlannerTaxonomyPort taxonomy;

    @Autowired
    public ChengduPlainTopographyStrategy(PlannerTaxonomyPort taxonomy) {
        this.taxonomy = taxonomy == null ? TaxonomyPort.empty() : taxonomy;
    }

    public ChengduPlainTopographyStrategy() { this(TaxonomyPort.empty()); }

    @Override
    public String supportedCity() {
        return "CHENGDU";
    }

    @Override
    public ElevationTier resolveElevationTier(Attraction attraction) {
        if (attraction == null) return ElevationTier.STANDARD_PLAIN;
        String name = attraction.getName() != null ? attraction.getName() : "";
        String id = attraction.getId() == null ? "" : attraction.getId();
        if (matches(name, "chengduTopoHighNameTokens") || matches(id, "chengduTopoHighIdTokens")) {
            return ElevationTier.HIGH_ALTITUDE;
        }
        return ElevationTier.STANDARD_PLAIN;
    }

    @Override
    public ElevationTier resolveElevationTier(String id, String name, String district, String location) {
        if (matches(name, "chengduTopoHighNameTokens") || matches(id, "chengduTopoHighIdTokens")) {
            return ElevationTier.HIGH_ALTITUDE;
        }
        return ElevationTier.STANDARD_PLAIN;
    }

    @Override
    public BarrierCrossingType detectBarrierCrossing(Attraction from, Attraction to) {
        // This city-specific strategy models plain-city movement without a configured major barrier.
        return BarrierCrossingType.NONE;
    }

    @Override
    public ElevationFlow detectElevationFlow(Attraction from, Attraction to) {
        // 平原城市绝大部分动线均为平级流动
        ElevationTier t1 = resolveElevationTier(from);
        ElevationTier t2 = resolveElevationTier(to);
        if (t1 == ElevationTier.HIGH_ALTITUDE && t2 == ElevationTier.STANDARD_PLAIN) return ElevationFlow.DOWNHILL;
        if (t1 == ElevationTier.STANDARD_PLAIN && t2 == ElevationTier.HIGH_ALTITUDE) return ElevationFlow.UPHILL;
        return ElevationFlow.FLAT;
    }

    @Override
    public double calculateZigZagPenalty(List<Attraction> historyStopsInDay, Attraction candidate) {
        if (historyStopsInDay == null || historyStopsInDay.size() < 2 || candidate == null) return 0.0;
        int size = historyStopsInDay.size();
        Attraction last = historyStopsInDay.get(size - 1);
        Attraction secondLast = historyStopsInDay.get(size - 2);

        boolean sDistant = isDistantSuburban(secondLast);
        boolean lDistant = isDistantSuburban(last);
        boolean cDistant = isDistantSuburban(candidate);

        if ((sDistant && !lDistant && cDistant) || (!sDistant && lDistant && !cDistant)) {
            return penalty("chengduTopoZigzagPenalty");
        }
        return 0.0;
    }

    private boolean isDistantSuburban(Attraction a) {
        if (a == null) return false;
        String dist = a.getDistrict() != null ? a.getDistrict() : "";
        String name = a.getName() != null ? a.getName() : "";
        return matches(dist, "chengduTopoOutskirtDistrictTokens") || matches(name, "chengduTopoOutskirtNameTokens");
    }

    @Override
    public String generateTransitCharacteristicHint(Attraction from, Attraction to, String transportPreference) {
        if (from == null || to == null) return "";
        // Current timetables and route facts belong to the live route provider.
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
