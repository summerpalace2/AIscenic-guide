package com.ai.guide.domain.planner.gateway;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.model.topography.BarrierCrossingType;
import com.ai.guide.domain.planner.model.topography.ElevationFlow;
import com.ai.guide.domain.planner.model.topography.ElevationTier;

import java.util.List;

/**
 * 城市时空阻抗与地理拓扑策略领域端口 (Domain Strategy Port)
 *
 * 架构定位：COLA 领域网关端口 (Gateway Port)
 * 职责：抽象不同城市特有的地形地貌、垂直落差、江河天堑与交通阻抗算法，
 *       为排程引擎提供标准化的时空代价与折返惩罚，彻底消除对特定城市地名的硬编码。
 */
public interface TopographyStrategyPort {

    /**
     * 该策略支持的目标城市标识（如 "CHONGQING", "CHENGDU", "XIAN"）
     */
    String supportedCity();

    /**
     * 解析景点的空间高程圈层
     *
     * @param attraction 目标打卡点实体
     * @return 空间高程圈层枚举
     */
    ElevationTier resolveElevationTier(Attraction attraction);

    /**
     * 解析景点的空间高程圈层（基于属性直接解析）
     */
    ElevationTier resolveElevationTier(String id, String name, String district, String location);

    /**
     * 检测两点之间是否存在跨越天然地理阻隔（如大江大河、古城墙、山体断层）
     *
     * @param from 起点
     * @param to   终点
     * @return 地理阻隔跨越分类
     */
    BarrierCrossingType detectBarrierCrossing(Attraction from, Attraction to);

    /**
     * 判断两站之间的垂直高程变化趋势（顺山势下行、逆势爬坡或平街流动）
     *
     * @param from 起点
     * @param to   终点
     * @return 高程流向
     */
    ElevationFlow detectElevationFlow(Attraction from, Attraction to);

    /**
     * 计算日内“折返惩罚（Zig-zag Penalty）”
     * 杜绝同一天在“山顶 ↔ 江边 ↔ 山顶”来回坐过山车，或在阻隔两侧无意义反复横跳。
     *
     * @param historyStopsInDay 当天已有游览站点列表
     * @param candidate         候选下一个站点
     * @return 惩罚扣分值（正浮点数，0.0 表示无惩罚）
     */
    double calculateZigZagPenalty(List<Attraction> historyStopsInDay, Attraction candidate);

    /**
     * 生成具有强烈地域辨识度的立体交通特色体验建议（锦囊）
     *
     * @param from                起点
     * @param to                  终点
     * @param transportPreference 用户交通偏好
     * @return 结构化通勤指引文案，无特殊特征时返回空字符串
     */
    String generateTransitCharacteristicHint(Attraction from, Attraction to, String transportPreference);

    /**
     * 首日起步阶段是否允许根据“出发地邻近度”进行加分（防止因初始加分强制拉扯跨江）
     */
    boolean allowStartPlaceProximity(Attraction previous, Attraction candidate);
}
