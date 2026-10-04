package com.ai.guide.domain.planner.gateway;

import com.ai.guide.domain.planner.model.PoiOutcome;
import com.ai.guide.domain.planner.model.RoutePairOutcome;
import com.ai.guide.domain.planner.model.WeatherOutcome;

/**
 * 地理与多模式路线服务领域网关端口 (Domain Gateway Port)
 *
 * 架构定位：COLA 领域网关端口 (Domain Gateway Port)
 * 职责：定义 POI 搜索、周边检索、POI 详情、起终点路线计算与天气查询的标准契约，
 *       彻底消除规划引擎、地标装配与美食餐饮领域服务对高德等具体第三方 SDK 基础设施的反向依赖。
 */
public interface GeoRouteGatewayPort {

    /**
     * 判断底层地理与路线服务是否已配置可用密钥
     *
     * @return true 表示已就绪，false 表示处于演示/降级模式
     */
    boolean isConfigured();

    /**
     * 根据关键词在指定城市检索 POI 候选
     *
     * @param query 搜索关键词
     * @param city  城市名称
     * @return POI 检索结果领域模型
     */
    PoiOutcome searchPoi(String query, String city);

    /**
     * 根据关键词、城市与指定类型检索 POI 候选
     *
     * @param query 搜索关键词
     * @param city  城市名称
     * @param types POI 类型过滤
     * @return POI 检索结果领域模型
     */
    PoiOutcome searchPoi(String query, String city, String types);

    /**
     * 以指定坐标为中心检索周边 POI
     *
     * @param coordinate   中心点经纬度坐标
     * @param radiusMeters 检索半径（米）
     * @param types        POI 类型编码或名称
     * @param keywords     过滤关键词
     * @param city         城市名称
     * @return POI 检索结果领域模型
     */
    PoiOutcome searchAround(String coordinate, int radiusMeters, String types, String keywords, String city);

    /**
     * 以指定坐标为中心检索周边 POI（带分页检索深度控制）
     *
     * @param coordinate   中心点经纬度坐标
     * @param radiusMeters 检索半径（米）
     * @param types        POI 类型编码或名称
     * @param keywords     过滤关键词
     * @param city         城市名称
     * @param pages        分页检索深度（取值 1~6 页，高德底层每页 25 条）
     * @return POI 检索结果领域模型
     */
    PoiOutcome searchAround(String coordinate, int radiusMeters, String types, String keywords, String city, int pages);

    /**
     * 获取指定 POI ID 的详细信息
     *
     * @param poiId POI 唯一标识
     * @return POI 详情领域模型
     */
    PoiOutcome poiDetail(String poiId);

    /**
     * 计算起终点之间的多模式通行路线
     *
     * @param originCoordinate      起点坐标
     * @param destinationCoordinate 终点坐标
     * @param city                  所属城市
     * @param originKeyword         起点名称/关键词
     * @param destinationKeyword    终点名称/关键词
     * @param preference            出行偏好（如 walking, transit）
     * @return 点对点多模式综合路线决策结果
     */
    RoutePairOutcome routePair(String originCoordinate, String destinationCoordinate, String city,
                              String originKeyword, String destinationKeyword, String preference);

    /**
     * 查询指定城市的天气状况
     *
     * @param cityCode 城市名称或 adcode
     * @return 天气查询结果领域模型
     */
    WeatherOutcome weather(String cityCode);
}
