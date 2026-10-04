package com.ai.guide.application.attraction;

import com.ai.guide.common.model.Result;
import com.ai.guide.domain.attraction.model.Attraction;

import java.util.List;
import java.util.Map;

/**
 * 文旅地标与景点探索应用服务门面 (Application Service)
 *
 * 所属应用层：application.attraction
 * 职责：编排文旅地标多维查询、富媒体装配、高德动态景点查询以及跨域事实核验投影用例。
 */
public interface AttractionApplicationService {

    /**
     * 多条件查询景点列表（支持按所属区县 district、分类 category 进行筛选）
     *
     * @param district 区县筛选
     * @param category 分类筛选
     * @return 景点列表结果
     */
    Result<List<Attraction>> list(String district, String category);

    /**
     * 查询指定景点的完整结构化数据（普通受控地标富媒体或高德动态景点详情）
     *
     * @param id 景点唯一标识符或动态实体 ID
     * @return 景点详情结果
     */
    Result<?> get(String id);

    /**
     * 事实核验详情查询（动态景点 / Production V2 事实声明 / 用户行程投影回退）
     *
     * @param id 景点唯一标识符或动态实体 ID
     * @return 事实详情结果
     */
    Result<Map<String, Object>> getFacts(String id);
}
