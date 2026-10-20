package com.ai.guide.application.attraction;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionMediaService;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.attraction.service.RuntimeAttractionDetailService;
import com.ai.guide.domain.rag.gateway.ProductionV2FactQueryPort;
import com.ai.guide.domain.trip.gateway.TripPlanStorePort;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 文旅地标与景点探索应用服务实现
 *
 * 所属应用层：application.attraction
 * 职责：协调地标领域服务、媒体富化服务、跨域事实查询服务与动态景点解析服务。
 */
@Service
public class AttractionApplicationServiceImpl implements AttractionApplicationService {

    private final AttractionService attractionService;
    private final AttractionMediaService attractionMediaService;
    private final TripPlanStorePort tripPlanService;
    private final ProductionV2FactQueryPort productionV2FactQueryService;
    private final RuntimeAttractionDetailService runtimeAttractionDetailService;

    public AttractionApplicationServiceImpl(AttractionService attractionService,
                                            AttractionMediaService attractionMediaService,
                                            TripPlanStorePort tripPlanService,
                                            ProductionV2FactQueryPort productionV2FactQueryService,
                                            RuntimeAttractionDetailService runtimeAttractionDetailService) {
        this.attractionService = attractionService;
        this.attractionMediaService = attractionMediaService;
        this.tripPlanService = tripPlanService;
        this.productionV2FactQueryService = productionV2FactQueryService;
        this.runtimeAttractionDetailService = runtimeAttractionDetailService;
    }

    @Override
    public Result<List<Attraction>> list(String district, String category) {
        List<Attraction> list = attractionService.list(district, category);
        return Result.success("查询成功", list);
    }

    @Override
    public Result<List<Attraction>> list(String city, String district, String category) {
        List<Attraction> list = attractionService.list(city, district, category);
        return Result.success("查询成功", list);
    }

    @Override
    public Result<?> get(String id) {
        if (id != null && (id.startsWith("amap-") || id.startsWith("runtime-stop-"))) {
            Map<String, Object> runtimeDetail = runtimeAttractionDetailService.detail(id);
            return runtimeDetail == null
                    ? Result.error(404, "高德未返回该动态景点详情")
                    : Result.success("查询成功", runtimeDetail);
        }
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
    @Override
    public Result<Map<String, Object>> getFacts(String id) {
        if (id != null && (id.startsWith("amap-") || id.startsWith("runtime-stop-"))) {
            Map<String, Object> runtimeDetail = runtimeAttractionDetailService.detail(id);
            return runtimeDetail == null
                    ? Result.error(404, "高德未返回该动态景点详情")
                    : Result.success("查询成功", runtimeDetail);
        }
        Map<String, Object> detail = productionV2FactQueryService.detail(id);
        if (detail == null && !UserContext.isAnonymous()) {
            detail = tripPlanService.findAttraction(UserContext.getUserId(), id);
        }
        return detail == null ? Result.error(404, "暂无可核验的景点详情") : Result.success("查询成功", detail);
    }
}
