package com.ai.guide.domain.planner.service.strategy;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
import com.ai.guide.domain.planner.model.topography.BarrierCrossingType;
import com.ai.guide.domain.planner.model.topography.ElevationFlow;
import com.ai.guide.domain.planner.model.topography.ElevationTier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通用平原与常规地形时空阻抗策略 (Generic Plain Topography Strategy)
 *
 * 架构职责：作为全国绝大多数平原、海滨及浅丘城市的基准通用策略，
 *           避免把某一城市的水系与高差策略套用到其他城市。
 */
@Component
public class GenericPlainTopographyStrategy implements TopographyStrategyPort {

    @Override
    public String supportedCity() {
        return "GENERIC";
    }

    @Override
    public ElevationTier resolveElevationTier(Attraction attraction) {
        return ElevationTier.UNVERIFIED;
    }

    @Override
    public ElevationTier resolveElevationTier(String id, String name, String district, String location) {
        return ElevationTier.UNVERIFIED;
    }

    @Override
    public BarrierCrossingType detectBarrierCrossing(Attraction from, Attraction to) {
        return BarrierCrossingType.NONE;
    }

    @Override
    public ElevationFlow detectElevationFlow(Attraction from, Attraction to) {
        ElevationTier t1 = resolveElevationTier(from);
        ElevationTier t2 = resolveElevationTier(to);
        if (t1 == ElevationTier.HIGH_ALTITUDE && t2 == ElevationTier.STANDARD_PLAIN) return ElevationFlow.DOWNHILL;
        if (t1 == ElevationTier.STANDARD_PLAIN && t2 == ElevationTier.HIGH_ALTITUDE) return ElevationFlow.UPHILL;
        return ElevationFlow.FLAT;
    }

    @Override
    public double calculateZigZagPenalty(List<Attraction> historyStopsInDay, Attraction candidate) {
        return 0.0;
    }

    @Override
    public String generateTransitCharacteristicHint(Attraction from, Attraction to, String transportPreference) {
        return "";
    }

    @Override
    public boolean allowStartPlaceProximity(Attraction previous, Attraction candidate) {
        return true;
    }
}
