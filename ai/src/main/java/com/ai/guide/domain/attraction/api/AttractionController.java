package com.ai.guide.domain.attraction.api;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.AttractionMediaService;
import com.ai.guide.domain.rag.service.ProductionV2FactQueryService;
import com.ai.guide.domain.trip.service.TripPlanService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

/**
 * 受控文旅地标与景点探索控制器
 *
 * 所属领域：domain.attraction（文旅地标与百科域）
 * 架构职责：作为重庆 24 大核心文旅地标元数据的唯一权威下发方，提供景点检索、分类过滤、详情富媒体展示及事实核验信息。
 *
 * 核心对外接口与关键方法：
 * 1. list：多条件查询景点列表（支持按所属区县 district、分类 category 进行筛选）
 * 2. get：查询指定景点的完整结构化数据（含门票、开放时间、游玩建议、高清配图等多媒体字段）
 * 3. getFacts：向下兼容查询景点的已核验证实事实与详情
 */
@RestController
@RequestMapping("/ai/attractions")
public class AttractionController {

    private final AttractionService attractionService;
    private final AttractionMediaService attractionMediaService;
    private final TripPlanService tripPlanService;
    private final ProductionV2FactQueryService productionV2FactQueryService;

    public AttractionController(AttractionService attractionService,
                                AttractionMediaService attractionMediaService,
                                TripPlanService tripPlanService,
                                ProductionV2FactQueryService productionV2FactQueryService) {
        this.attractionService = attractionService;
        this.attractionMediaService = attractionMediaService;
        this.tripPlanService = tripPlanService;
        this.productionV2FactQueryService = productionV2FactQueryService;
    }

    @GetMapping
    public Result<List<Attraction>> list(
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String category) {
        List<Attraction> list = attractionService.list(district, category);
        return Result.success("查询成功", list);
    }

    @GetMapping("/{id}")
    public Result<Attraction> get(@PathVariable String id) {
        Attraction attraction = attractionService.get(id);
        if (attraction == null) {
            return Result.error(404, "景点不存在");
        }
        return Result.success("查询成功", attractionMediaService.enrich(attraction));
    }

    /**
     * 保持向下兼容：优先从 Production V2 事实声明获取详情；没有时回退到用户保存的行程投影，
     * 不得将旧 Qdrant 命中提升为事实。
     */
    @GetMapping("/{id}/facts")
    public Result<Map<String, Object>> getFacts(@PathVariable String id) {
        Map<String, Object> detail = productionV2FactQueryService.detail(id);
        if (detail == null && !UserContext.isAnonymous()) {
            detail = tripPlanService.findAttraction(UserContext.getUserId(), id);
        }
        return detail == null ? Result.error(404, "暂无可核验的景点详情") : Result.success("查询成功", detail);
    }
}
