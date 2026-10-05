package com.ai.guide.domain.planner.service.strategy;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
import com.ai.guide.domain.planner.model.topography.BarrierCrossingType;
import com.ai.guide.domain.planner.model.topography.ElevationFlow;
import com.ai.guide.domain.planner.model.topography.ElevationTier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChengduPlainTopographyStrategy implements TopographyStrategyPort {

    @Override
    public String supportedCity() {
        return "CHENGDU";
    }

    @Override
    public ElevationTier resolveElevationTier(Attraction attraction) {
        if (attraction == null) return ElevationTier.STANDARD_PLAIN;
        String name = attraction.getName() != null ? attraction.getName() : "";
        if (name.contains("青城山") || name.contains("西岭雪山") || name.contains("龙泉山") || name.contains("天台山")) {
            return ElevationTier.HIGH_ALTITUDE;
        }
        return ElevationTier.STANDARD_PLAIN;
    }

    @Override
    public ElevationTier resolveElevationTier(String id, String name, String district, String location) {
        if (name != null && (name.contains("青城山") || name.contains("西岭雪山") || name.contains("龙泉山") || name.contains("天台山"))) {
            return ElevationTier.HIGH_ALTITUDE;
        }
        return ElevationTier.STANDARD_PLAIN;
    }

    @Override
    public BarrierCrossingType detectBarrierCrossing(Attraction from, Attraction to) {
        // 成都无天然大江天堑阻隔，锦江两岸桥梁密布，阻抗极低
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
            return 70.0; // 严禁同一天内进出远郊往返折腾
        }
        return 0.0;
    }

    private boolean isDistantSuburban(Attraction a) {
        if (a == null) return false;
        String dist = a.getDistrict() != null ? a.getDistrict() : "";
        String name = a.getName() != null ? a.getName() : "";
        return dist.contains("都江堰") || dist.contains("崇州") || dist.contains("大邑") || name.contains("青城山");
    }

    @Override
    public String generateTransitCharacteristicHint(Attraction from, Attraction to, String transportPreference) {
        if (from == null || to == null) return "";
        String n1 = from.getName() != null ? from.getName() : "";
        String n2 = to.getName() != null ? to.getName() : "";
        if (n1.contains("春熙路") && n2.contains("宽窄巷子")) {
            return "两站横贯成都母城东西核心，推荐乘坐地铁2号线直达（约15分钟），亦可骑行共享单车漫游天府市井街巷。";
        }
        if (n2.contains("青城山") || n2.contains("都江堰")) {
            return "前往都江堰/青城山片区，极力推荐在犀浦站换乘城际动车（快铁30分钟直达），快捷且避开成灌高速拥堵。";
        }
        return "";
    }

    @Override
    public boolean allowStartPlaceProximity(Attraction previous, Attraction candidate) {
        return true;
    }
}
