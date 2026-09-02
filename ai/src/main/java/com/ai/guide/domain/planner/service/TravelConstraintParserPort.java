package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.TravelConstraints;
import java.util.Map;

/**
 * 出行约束解析器端口契约接口
 *
 * 所属领域：domain.planner.service（规划引擎服务层）
 */
public interface TravelConstraintParserPort {

    TravelConstraints parse(String prompt);

    TravelConstraints applyOverrides(TravelConstraints base, Map<String, Object> overrides);

    TravelConstraints applyDerivedTransportFallback(TravelConstraints base);
}
