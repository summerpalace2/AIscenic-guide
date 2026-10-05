package com.ai.guide.domain.planner.service.strategy;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
import com.ai.guide.domain.planner.model.topography.BarrierCrossingType;
import com.ai.guide.domain.planner.model.topography.ElevationFlow;
import com.ai.guide.domain.planner.model.topography.ElevationTier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class XiAnHeritageTopographyStrategy implements TopographyStrategyPort {

    @Override
    public String supportedCity() {
        return "XIAN";
    }

    @Override
    public ElevationTier resolveElevationTier(Attraction attraction) {
        if (attraction == null) return ElevationTier.STANDARD_PLAIN;
        String name = attraction.getName() != null ? attraction.getName() : "";
        if (name.contains("华山") || name.contains("终南山") || name.contains("翠华山")) {
            return ElevationTier.HIGH_ALTITUDE;
        }
        return ElevationTier.STANDARD_PLAIN;
    }

    @Override
    public ElevationTier resolveElevationTier(String id, String name, String district, String location) {
        if (name != null && (name.contains("华山") || name.contains("终南山") || name.contains("翠华山"))) {
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
            return BarrierCrossingType.HISTORICAL_WALL; // 穿越南门/永宁门等城墙节点
        }
        return BarrierCrossingType.NONE;
    }

    private boolean isInsideCityWall(Attraction a) {
        String name = a.getName() != null ? a.getName() : "";
        String dist = a.getDistrict() != null ? a.getDistrict() : "";
        return (dist.contains("碑林") || dist.contains("莲湖")) &&
                (name.contains("钟楼") || name.contains("鼓楼") || name.contains("回民街") || name.contains("书院门") || name.contains("碑林"));
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

        // 临潼兵马俑远郊反复折返检测
        boolean sLintong = isLintongArea(secondLast);
        boolean lLintong = isLintongArea(last);
        boolean cLintong = isLintongArea(candidate);
        if ((sLintong && !lLintong && cLintong) || (!sLintong && lLintong && !cLintong)) {
            return 80.0; // 兵马俑远郊严禁往返拉扯
        }
        return 0.0;
    }

    private boolean isLintongArea(Attraction a) {
        if (a == null) return false;
        String name = a.getName() != null ? a.getName() : "";
        String dist = a.getDistrict() != null ? a.getDistrict() : "";
        return dist.contains("临潼") || name.contains("兵马俑") || name.contains("华清宫");
    }

    @Override
    public String generateTransitCharacteristicHint(Attraction from, Attraction to, String transportPreference) {
        if (from == null || to == null) return "";
        BarrierCrossingType crossing = detectBarrierCrossing(from, to);
        if (crossing == BarrierCrossingType.HISTORICAL_WALL) {
            return "路线穿越明城墙护城河进入古城内核，推荐经由永宁门（南门）步行漫游古城石阶，或乘坐地铁2号线纵贯中轴线。";
        }
        String n2 = to.getName() != null ? to.getName() : "";
        if (n2.contains("兵马俑") || n2.contains("华清池")) {
            return "前往临潼历史文化带，推荐乘坐地铁9号线至华清池/兵马俑专线，快速直达且避开外环交通拥堵。";
        }
        return "";
    }

    @Override
    public boolean allowStartPlaceProximity(Attraction previous, Attraction candidate) {
        return true;
    }
}
