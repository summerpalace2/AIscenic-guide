package com.ai.guide.domain.planner.service.strategy;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.gateway.TopographyStrategyPort;
import com.ai.guide.domain.planner.model.topography.BarrierCrossingType;
import com.ai.guide.domain.planner.model.topography.ElevationFlow;
import com.ai.guide.domain.planner.model.topography.ElevationTier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class ChongqingTopographyStrategy implements TopographyStrategyPort {

    @Override
    public String supportedCity() {
        return "CHONGQING";
    }

    @Override
    public ElevationTier resolveElevationTier(Attraction attraction) {
        if (attraction == null) return ElevationTier.STANDARD_PLAIN;
        return resolveElevationTier(attraction.getId(), attraction.getName(), attraction.getDistrict(), attraction.getLocation());
    }

    @Override
    public ElevationTier resolveElevationTier(String id, String name, String district, String location) {
        String cleanId = id != null ? id.toLowerCase(Locale.ROOT) : "";
        String cleanName = name != null ? name : "";
        String cleanDist = district != null ? district : "";

        // 1. 高山制高点（南山、歌乐山、照母山、仙女山）
        if (cleanName.contains("南山") || cleanName.contains("一棵树") || cleanName.contains("黄桷垭")
                || cleanName.contains("歌乐山") || cleanName.contains("白公馆") || cleanName.contains("渣滓洞")
                || cleanName.contains("仙女山") || cleanName.contains("天生三桥")
                || cleanId.contains("nanshan") || cleanId.contains("huangjueya")) {
            return ElevationTier.HIGH_ALTITUDE;
        }

        // 2. 滨江沿线低洼带（江面 180~210 米标高）
        if (cleanName.contains("洪崖洞") || cleanName.contains("李子坝") || cleanName.contains("朝天门")
                || cleanName.contains("白象街") || cleanName.contains("南滨路") || cleanName.contains("大剧院")
                || cleanName.contains("磁器口") || cleanName.contains("码头") || cleanName.contains("江滩")
                || cleanId.contains("hongyadong") || cleanId.contains("liziba") || cleanId.contains("chaotianmen")) {
            return ElevationTier.LOWLAND_VALLEY;
        }

        // 3. 上半城/山脊台地（解放碑、鹅岭、大坪）
        if ("渝中区".equals(cleanDist)) {
            return ElevationTier.UPPER_PLATEAU;
        }
        if (cleanName.contains("观音桥") || cleanName.contains("九街")) {
            return ElevationTier.UPPER_PLATEAU;
        }

        return ElevationTier.STANDARD_PLAIN;
    }

    @Override
    public BarrierCrossingType detectBarrierCrossing(Attraction from, Attraction to) {
        if (from == null || to == null) return BarrierCrossingType.NONE;
        String d1 = from.getDistrict() != null ? from.getDistrict().trim() : "";
        String d2 = to.getDistrict() != null ? to.getDistrict().trim() : "";
        if (d1.isBlank() || d2.isBlank() || d1.equals(d2)) return BarrierCrossingType.NONE;

        boolean fromJialingSouth = "渝中区".equals(d1) || "沙坪坝区".equals(d1);
        boolean toJialingNorth = "江北区".equals(d2) || "渝北区".equals(d2);
        boolean fromJialingNorth = "江北区".equals(d1) || "渝北区".equals(d1);
        boolean toJialingSouth = "渝中区".equals(d2) || "沙坪坝区".equals(d2);
        if ((fromJialingSouth && toJialingNorth) || (fromJialingNorth && toJialingSouth)) {
            return BarrierCrossingType.MAJOR_RIVER_PRIMARY; // 嘉陵江
        }

        boolean fromYangtzeNorth = "渝中区".equals(d1) || "九龙坡区".equals(d1) || "大渡口区".equals(d1);
        boolean toYangtzeSouth = "南岸区".equals(d2) || "巴南区".equals(d2);
        boolean fromYangtzeSouth = "南岸区".equals(d1) || "巴南区".equals(d1);
        boolean toYangtzeNorth = "渝中区".equals(d2) || "九龙坡区".equals(d2) || "大渡口区".equals(d2);
        if ((fromYangtzeNorth && toYangtzeSouth) || (fromYangtzeSouth && toYangtzeNorth)) {
            return BarrierCrossingType.MAJOR_RIVER_SECONDARY; // 长江
        }

        if (("江北区".equals(d1) && "南岸区".equals(d2)) || ("南岸区".equals(d1) && "江北区".equals(d2))) {
            return BarrierCrossingType.CONFLUENCE_BOTH;
        }
        return BarrierCrossingType.NONE;
    }

    @Override
    public ElevationFlow detectElevationFlow(Attraction from, Attraction to) {
        if (from == null || to == null) return ElevationFlow.FLAT;
        String n1 = from.getName() != null ? from.getName() : "";
        String n2 = to.getName() != null ? to.getName() : "";

        if ((n1.contains("鹅岭") && n2.contains("李子坝"))
                || (n1.contains("解放碑") && n2.contains("洪崖洞"))
                || (n1.contains("解放碑") && n2.contains("十八梯"))
                || (n1.contains("南山") && n2.contains("南滨路"))) {
            return ElevationFlow.DOWNHILL;
        }
        if ((n1.contains("李子坝") && n2.contains("鹅岭"))
                || (n1.contains("洪崖洞") && n2.contains("解放碑"))
                || (n1.contains("十八梯") && n2.contains("解放碑"))
                || (n2.contains("南山") && n1.contains("南滨路"))) {
            return ElevationFlow.UPHILL;
        }

        ElevationTier t1 = resolveElevationTier(from);
        ElevationTier t2 = resolveElevationTier(to);
        if (t1.getRank() > t2.getRank()) return ElevationFlow.DOWNHILL;
        if (t1.getRank() < t2.getRank()) return ElevationFlow.UPHILL;
        return ElevationFlow.FLAT;
    }

    @Override
    public double calculateZigZagPenalty(List<Attraction> historyStopsInDay, Attraction candidate) {
        if (historyStopsInDay == null || historyStopsInDay.size() < 2 || candidate == null) return 0.0;
        double penalty = 0.0;
        int size = historyStopsInDay.size();
        Attraction last = historyStopsInDay.get(size - 1);
        Attraction secondLast = historyStopsInDay.get(size - 2);

        ElevationTier cTier = resolveElevationTier(candidate);
        ElevationTier lTier = resolveElevationTier(last);
        ElevationTier sTier = resolveElevationTier(secondLast);

        // 1. 高度反弹折返
        boolean isBounce = (sTier == ElevationTier.HIGH_ALTITUDE && lTier == ElevationTier.LOWLAND_VALLEY && cTier == ElevationTier.HIGH_ALTITUDE)
                || (sTier == ElevationTier.LOWLAND_VALLEY && lTier == ElevationTier.HIGH_ALTITUDE && cTier == ElevationTier.LOWLAND_VALLEY);
        if (isBounce) {
            penalty += 60.0;
        }

        // 2. 跨江反复横跳
        BarrierCrossingType c1 = detectBarrierCrossing(secondLast, last);
        BarrierCrossingType c2 = detectBarrierCrossing(last, candidate);
        if (c1 != BarrierCrossingType.NONE && c1 == c2) {
            penalty += 45.0;
        }
        return penalty;
    }

    @Override
    public String generateTransitCharacteristicHint(Attraction from, Attraction to, String transportPreference) {
        if (from == null || to == null) return "";
        String n1 = from.getName() != null ? from.getName() : "";
        String n2 = to.getName() != null ? to.getName() : "";

        if (n1.contains("鹅岭") && n2.contains("李子坝")) {
            return "顺山势从鹅岭二厂沿三层马路步道惬意下行，直达李子坝单轨观景平台，下坡舒适平缓。";
        }
        if (n1.contains("两路口") && n2.contains("菜园坝")) {
            return "建议体验山城特色【皇冠大扶梯】（落差52.7米），顺势下坡直达菜园坝江畔。";
        }
        BarrierCrossingType crossing = detectBarrierCrossing(from, to);
        if (crossing == BarrierCrossingType.MAJOR_RIVER_PRIMARY) {
            return "两站跨越嘉陵江，推荐乘坐轨道交通2号线/6号线体验跨江视觉，或打车经千厮门大桥通达。";
        }
        if (crossing == BarrierCrossingType.MAJOR_RIVER_SECONDARY) {
            return "两站横跨长江，推荐体验标志性【长江索道】空中飞渡，或乘坐轨道环线快速过江。";
        }
        return "";
    }

    @Override
    public boolean allowStartPlaceProximity(Attraction previous, Attraction candidate) {
        return previous == null || detectBarrierCrossing(previous, candidate) == BarrierCrossingType.NONE;
    }
}
