package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.attraction.model.Attraction;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;

/**
 * 重庆立体地貌与空间水系知识库
 *
 * 架构职责：
 * 1. 划分山城独特的垂直高程圈层（高山/半山、山脊台地上半城、滨江沿线下半城、常规浅丘平街）；
 * 2. 识别长江与嘉陵江天堑天然分界，捕捉跨江轨迹；
 * 3. 评估动线高程流向（顺山势下行 DOWNHILL vs 逆山势爬坡 UPHILL）；
 * 4. 计算日内“折返惩罚（Zig-zag Penalty）”，杜绝同一天在“山顶 ↔ 江边 ↔ 山顶”来回坐过山车或反复横跳跨江；
 * 5. 注入具有强烈辨识度的山城特色立体交通锦囊（皇冠大扶梯、长江索道、千厮门大桥漫步、单轨穿江等）。
 */
@Slf4j
public final class ChongqingTopographyKnowledge {

    private ChongqingTopographyKnowledge() {}

    /**
     * 空间高程圈层枚举
     */
    public enum ElevationTier {
        /** 高山/半山高海拔带（南山一棵树、黄桷垭老街、南山老厂、歌乐山森林公园、仙女山等，海拔 > 350m） */
        HIGH_MOUNTAIN(3, "高山/半山高地"),
        /** 上半城/山脊台地（解放碑、两路口、大坪、鹅岭公园/二厂、三峡博物馆等，海拔 250m ~ 380m） */
        UPPER_RIDGE(2, "山脊台地上半城"),
        /** 下半城/江岸平坦带（洪崖洞滨江、李子坝江岸、朝天门码头、菜园坝、十八梯底部、白象街、南滨路、大剧院等，海拔 180m ~ 210m） */
        LOWER_RIVER(1, "滨江沿线下半城"),
        /** 常规城区平街/浅丘（沙坪坝三峡广场、杨家坪步行街等） */
        STANDARD_URBAN(2, "平街浅丘商圈");

        private final int rank;
        private final String description;

        ElevationTier(int rank, String description) {
            this.rank = rank;
            this.description = description;
        }

        public int getRank() {
            return rank;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 天然江河跨江分类
     */
    public enum RiverCrossingType {
        NONE("无需跨江"),
        JIALING_RIVER("跨越嘉陵江"),
        YANGTZE_RIVER("跨越长江"),
        BOTH_RIVERS("贯穿两江交汇");

        private final String description;

        RiverCrossingType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 垂直坡度流向
     */
    public enum ElevationFlow {
        /** 顺势而下（高处向低处，轻松舒适，长辈友好） */
        DOWNHILL,
        /** 逆势而上（低处向高处，台阶坡度体力消耗大） */
        UPHILL,
        /** 平级流动 */
        FLAT
    }

    /**
     * 解析景点的空间高程圈层
     */
    public static ElevationTier resolveElevationTier(Attraction attraction) {
        if (attraction == null) return ElevationTier.STANDARD_URBAN;
        return resolveElevationTier(attraction.getId(), attraction.getName(), attraction.getDistrict(), attraction.getLocation());
    }

    public static ElevationTier resolveElevationTier(String id, String name, String district, String location) {
        String cleanId = id != null ? id.toLowerCase(Locale.ROOT) : "";
        String cleanName = name != null ? name : "";
        String cleanDist = district != null ? district : "";

        // 1. 高山高海拔地貌（南山、歌乐山、照母山、缙云山、仙女山）
        if (cleanName.contains("南山") || cleanName.contains("一棵树") || cleanName.contains("黄桷垭")
                || cleanName.contains("大金鹰") || cleanName.contains("植物园") || cleanName.contains("泉水鸡")
                || cleanName.contains("歌乐山") || cleanName.contains("白公馆") || cleanName.contains("渣滓洞")
                || cleanName.contains("缙云山") || cleanName.contains("仙女山") || cleanName.contains("天生三桥")
                || cleanId.contains("nanshan") || cleanId.contains("huangjueya") || cleanId.contains("geleshani")) {
            return ElevationTier.HIGH_MOUNTAIN;
        }

        // 2. 滨江/沿江下半城（落差在江面 180~210 米标高）
        if (cleanName.contains("洪崖洞") || cleanName.contains("李子坝") || cleanName.contains("朝天门")
                || cleanName.contains("来福士") || cleanName.contains("菜园坝") || cleanName.contains("白象街")
                || cleanName.contains("南滨路") || cleanName.contains("海棠烟雨") || cleanName.contains("龙门浩")
                || cleanName.contains("弹子石") || cleanName.contains("大剧院") || cleanName.contains("北滨路")
                || cleanName.contains("江滩") || cleanName.contains("码头") || cleanName.contains("滨江")
                || cleanId.contains("hongyadong") || cleanId.contains("liziba") || cleanId.contains("longmenhao")
                || cleanId.contains("danzishi") || cleanId.contains("grand-theatre") || cleanId.contains("chaotianmen")) {
            return ElevationTier.LOWER_RIVER;
        }

        // 3. 上半城/山脊台地（解放碑、鹅岭、大坪、两路口）
        if ("渝中区".equals(cleanDist)) {
            if (cleanName.contains("鹅岭") || cleanName.contains("二厂") || cleanId.contains("eling")) {
                return ElevationTier.UPPER_RIDGE; // 鹅岭为渝中山脊最高峰 (~380m)
            }
            if (cleanName.contains("解放碑") || cleanName.contains("两路口") || cleanName.contains("大坪")
                    || cleanName.contains("时代天街") || cleanName.contains("三峡博物馆") || cleanName.contains("人民大礼堂")
                    || cleanName.contains("中山四路") || cleanName.contains("文化宫") || cleanName.contains("琵琶山")
                    || cleanId.contains("jiefangbei") || cleanId.contains("museum") || cleanId.contains("great-hall")) {
                return ElevationTier.UPPER_RIDGE;
            }
        }

        // 江北嘴高台与观音桥台地
        if (cleanName.contains("观音桥") || cleanName.contains("九街")) {
            return ElevationTier.UPPER_RIDGE;
        }

        return ElevationTier.STANDARD_URBAN;
    }

    /**
     * 判断两点之间是否跨江（长江 / 嘉陵江）
     */
    public static RiverCrossingType detectRiverCrossing(Attraction from, Attraction to) {
        if (from == null || to == null) return RiverCrossingType.NONE;
        return detectRiverCrossing(from.getDistrict(), from.getName(), to.getDistrict(), to.getName());
    }

    public static RiverCrossingType detectRiverCrossing(String distFrom, String nameFrom, String distTo, String nameTo) {
        String d1 = distFrom != null ? distFrom.trim() : "";
        String d2 = distTo != null ? distTo.trim() : "";
        String n1 = nameFrom != null ? nameFrom : "";
        String n2 = nameTo != null ? nameTo : "";

        if (d1.isBlank() || d2.isBlank() || d1.equals(d2)) {
            return RiverCrossingType.NONE;
        }

        // 嘉陵江分界：
        // 南岸侧：渝中区、沙坪坝区
        // 北岸侧：江北区、渝北区
        boolean fromJialingSouth = "渝中区".equals(d1) || "沙坪坝区".equals(d1);
        boolean toJialingNorth = "江北区".equals(d2) || "渝北区".equals(d2);
        boolean fromJialingNorth = "江北区".equals(d1) || "渝北区".equals(d1);
        boolean toJialingSouth = "渝中区".equals(d2) || "沙坪坝区".equals(d2);

        if ((fromJialingSouth && toJialingNorth) || (fromJialingNorth && toJialingSouth)) {
            return RiverCrossingType.JIALING_RIVER;
        }

        // 长江分界：
        // 北岸侧：渝中区、九龙坡区、大渡口区
        // 南岸侧：南岸区、巴南区
        boolean fromYangtzeNorth = "渝中区".equals(d1) || "九龙坡区".equals(d1) || "大渡口区".equals(d1);
        boolean toYangtzeSouth = "南岸区".equals(d2) || "巴南区".equals(d2);
        boolean fromYangtzeSouth = "南岸区".equals(d1) || "巴南区".equals(d1);
        boolean toYangtzeNorth = "渝中区".equals(d2) || "九龙坡区".equals(d2) || "大渡口区".equals(d2);

        if ((fromYangtzeNorth && toYangtzeSouth) || (fromYangtzeSouth && toYangtzeNorth)) {
            return RiverCrossingType.YANGTZE_RIVER;
        }

        // 跨越两江（例如从江北区直接到南岸区）
        if (("江北区".equals(d1) && "南岸区".equals(d2)) || ("南岸区".equals(d1) && "江北区".equals(d2))) {
            return RiverCrossingType.BOTH_RIVERS;
        }

        return RiverCrossingType.NONE;
    }

    /**
     * 判断两站之间的垂直高程变化趋势
     */
    public static ElevationFlow detectElevationFlow(Attraction from, Attraction to) {
        if (from == null || to == null) return ElevationFlow.FLAT;

        // 特殊地标点对（微观山城专属黄金通道）
        String n1 = from.getName() != null ? from.getName() : "";
        String n2 = to.getName() != null ? to.getName() : "";

        if ((n1.contains("鹅岭") && n2.contains("李子坝"))
                || (n1.contains("两路口") && n2.contains("菜园坝"))
                || (n1.contains("解放碑") && n2.contains("十八梯"))
                || (n1.contains("解放碑") && n2.contains("洪崖洞"))
                || (n1.contains("南山") && (n2.contains("南滨路") || n2.contains("龙门浩")))) {
            return ElevationFlow.DOWNHILL;
        }

        if ((n1.contains("李子坝") && n2.contains("鹅岭"))
                || (n1.contains("菜园坝") && n2.contains("两路口"))
                || (n1.contains("十八梯") && n2.contains("解放碑"))
                || (n1.contains("洪崖洞") && n2.contains("解放碑"))
                || ((n1.contains("南滨路") || n1.contains("龙门浩")) && n2.contains("南山"))) {
            return ElevationFlow.UPHILL;
        }

        ElevationTier tier1 = resolveElevationTier(from);
        ElevationTier tier2 = resolveElevationTier(to);

        if (tier1.getRank() > tier2.getRank()) {
            return ElevationFlow.DOWNHILL;
        } else if (tier1.getRank() < tier2.getRank()) {
            return ElevationFlow.UPHILL;
        }
        return ElevationFlow.FLAT;
    }

    /**
     * 计算折返惩罚（Zig-zag Penalty）：杜绝同一天在“山顶 ↔ 江边 ↔ 山顶”来回坐过山车，或无意义反复跨江
     *
     * @param historyStopsInDay 当天已有站点列表
     * @param candidate         候选下一个站点
     * @return 惩罚扣分值（正整数，0 表示无惩罚）
     */
    public static double calculateZigZagPenalty(List<Attraction> historyStopsInDay, Attraction candidate) {
        if (historyStopsInDay == null || historyStopsInDay.size() < 2 || candidate == null) {
            return 0.0;
        }

        double penalty = 0.0;
        int size = historyStopsInDay.size();
        Attraction last = historyStopsInDay.get(size - 1);
        Attraction secondLast = historyStopsInDay.get(size - 2);

        ElevationTier candidateTier = resolveElevationTier(candidate);
        ElevationTier lastTier = resolveElevationTier(last);
        ElevationTier secondLastTier = resolveElevationTier(secondLast);

        // 1. 高山-滨江折返检测（如 南山 -> 南滨路 -> 再次返回南山，或者 鹅岭 -> 李子坝 -> 再次折回鹅岭）
        boolean isMountainBounce = (secondLastTier == ElevationTier.HIGH_MOUNTAIN && lastTier == ElevationTier.LOWER_RIVER && candidateTier == ElevationTier.HIGH_MOUNTAIN)
                || (secondLastTier == ElevationTier.LOWER_RIVER && lastTier == ElevationTier.HIGH_MOUNTAIN && candidateTier == ElevationTier.LOWER_RIVER);
        if (isMountainBounce) {
            log.info("[Topography] 捕获上下山高差折返模式: {} -> {} -> {}, 施加高额折返惩罚",
                    secondLast.getName(), last.getName(), candidate.getName());
            penalty += 60.0;
        }

        // 2. 跨江反复横跳检测（如 渝中区 -> 南岸区 -> 再次返回渝中区，且此时行程只有3站，属于无意义横渡）
        RiverCrossingType lastCrossing = detectRiverCrossing(secondLast, last);
        RiverCrossingType nextCrossing = detectRiverCrossing(last, candidate);
        if (lastCrossing != RiverCrossingType.NONE && lastCrossing == nextCrossing) {
            // 刚刚跨完某条江，下一站立刻又跨回去
            log.info("[Topography] 捕获同一日内频繁跨江折返: {} -> {} -> {} ({}), 施加跨江惩罚",
                    secondLast.getName(), last.getName(), candidate.getName(), lastCrossing);
            penalty += 45.0;
        }

        return penalty;
    }

    /**
     * 为跨江与上下山两站生成具有地道重庆辨识度的立体交通特色体验建议
     */
    public static String generateTransitCharacteristicHint(Attraction from, Attraction to, String transportPreference) {
        if (from == null || to == null) return "";

        String n1 = from.getName() != null ? from.getName() : "";
        String n2 = to.getName() != null ? to.getName() : "";

        // 1. 经典垂直地貌黄金廊道
        if (n1.contains("鹅岭") && n2.contains("李子坝")) {
            return "顺山势从鹅岭二厂沿三层马路步道惬意下行，沿途眺望嘉陵江景并直达李子坝单轨穿楼观景平台，下坡舒适平缓。";
        }
        if (n1.contains("李子坝") && n2.contains("鹅岭")) {
            return "此段由李子坝前往鹅岭属于逆山势爬坡（落差超100米），步道多台阶，强烈建议打车或乘坐景区专线接驳上山，避免长辈劳累。";
        }
        if (n1.contains("两路口") && n2.contains("菜园坝")) {
            return "建议体验山城特色【皇冠大扶梯】（落差约52.7米，单程运行仅约2分半），顺势下坡直达菜园坝江畔。";
        }
        if (n1.contains("菜园坝") && n2.contains("两路口")) {
            return "下半城往上半城大坡度，建议乘坐【皇冠大扶梯】上行，免去高强度爬阶梯之苦。";
        }
        if (n1.contains("解放碑") && (n2.contains("十八梯") || n2.contains("白象街"))) {
            return "顺山势自上半城解放碑台地漫步下行，沿十八梯古朴石阶步入下半城，下坡惬意且一路体验地道母城风貌。";
        }
        if ((n1.contains("十八梯") || n1.contains("白象街")) && n2.contains("解放碑")) {
            return "自下半城返回上半城解放碑属于连续向上爬梯，体力有限或同行有长辈时，可借助扶梯或打车短驳直达平街。";
        }

        // 2. 跨越嘉陵江
        RiverCrossingType crossing = detectRiverCrossing(from, to);
        if (crossing == RiverCrossingType.JIALING_RIVER) {
            if ((n1.contains("洪崖洞") && n2.contains("大剧院")) || (n1.contains("大剧院") && n2.contains("洪崖洞"))) {
                return "两站隔嘉陵江相望，推荐步行【千厮门嘉陵江大桥】观光步道（沿途欣赏洪崖洞全景与江北嘴灯火），亦可乘坐轨道交通6号线（1站即达）。";
            }
            return "两站跨越嘉陵江，建议乘坐轨道交通2号线/6号线（体验跨江列车开阔江景），或打车经千厮门/黄花园大桥通达。";
        }

        // 3. 跨越长江
        if (crossing == RiverCrossingType.YANGTZE_RIVER) {
            if ((n1.contains("解放碑") && (n2.contains("南滨路") || n2.contains("龙门浩") || n2.contains("弹子石")))
                    || (n2.contains("解放碑") && (n1.contains("南滨路") || n1.contains("龙门浩")))) {
                return "两站横跨浩荡长江，极力推荐体验山城标志【长江索道】（空中巴士凌空飞渡），或乘坐地铁环线/打车经东水门大桥通达。";
            }
            return "两站跨越长江天堑，推荐打车经东水门大桥/菜园坝大桥通达，或乘坐轨道交通环线快速过江。";
        }

        // 4. 贯穿两江
        if (crossing == RiverCrossingType.BOTH_RIVERS) {
            return "此段行程横跨两江交汇带，推荐乘坐轨道交通环线/6号线快速贯通，或打车经朝天门大桥饱览朝天门两江风光。";
        }

        // 5. 南山高山与南滨江岸
        if (n1.contains("南山") && (n2.contains("南滨路") || n2.contains("龙门浩") || n2.contains("弹子石"))) {
            return "自南山高处眺望全城后顺山势下行，车程仅15-20分钟直达南滨路滨江漫步带，视野由高入远，张弛有度。";
        }
        if ((n1.contains("南滨路") || n1.contains("龙门浩")) && n2.contains("南山")) {
            return "前往南山高地山路盘旋陡峭，建议提前预约网约车或沿黄明路直达景区，避免夜间山路徒步。";
        }

        return "";
    }
}
