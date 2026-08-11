package com.ai.guide.controller;

import com.ai.guide.config.UserContext;
import com.ai.guide.model.Result;
import com.ai.guide.service.TripPlanService;
import com.ai.guide.service.ProductionV2FactQueryService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai/attractions")
public class AttractionController {

    private final TripPlanService tripPlanService;
    private final ProductionV2FactQueryService productionV2FactQueryService;

    public AttractionController(TripPlanService tripPlanService,
                                ProductionV2FactQueryService productionV2FactQueryService) {
        this.tripPlanService = tripPlanService;
        this.productionV2FactQueryService = productionV2FactQueryService;
    }

    /**
     * 优先从 Production V2 事实声明获取详情；没有时回退到用户保存的行程投影，
     * 不得将旧 Qdrant 命中提升为事实。
     */
    @GetMapping("/{entityId}")
    public Result<Map<String, Object>> get(@PathVariable String entityId) {
        Map<String, Object> detail = productionV2FactQueryService.detail(entityId);
        if (detail == null) detail = tripPlanService.findAttraction(UserContext.getUserId(), entityId);
        return detail == null ? Result.error(404, "暂无可核验的景点详情") : Result.success("查询成功", detail);
    }
}
