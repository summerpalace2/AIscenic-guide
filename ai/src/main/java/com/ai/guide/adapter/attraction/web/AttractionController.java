package com.ai.guide.adapter.attraction.web;

import com.ai.guide.application.attraction.AttractionApplicationService;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.attraction.model.Attraction;
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
 * 所属适配层：adapter.attraction.web
 * 架构职责：作为重庆 24+ 大核心文旅地标元数据的唯一权威下发入口，委派应用服务提供景点检索、分类过滤、详情富媒体展示及事实核验信息。
 */
@RestController
@RequestMapping("/ai/attractions")
public class AttractionController {

    private final AttractionApplicationService attractionApplicationService;

    public AttractionController(AttractionApplicationService attractionApplicationService) {
        this.attractionApplicationService = attractionApplicationService;
    }

    @GetMapping
    public Result<List<Attraction>> list(
            @RequestParam(required = false) String district,
            @RequestParam(required = false) String category) {
        return attractionApplicationService.list(district, category);
    }

    @GetMapping("/{id}")
    public Result<?> get(@PathVariable String id) {
        return attractionApplicationService.get(id);
    }

    /**
     * 保持向下兼容：优先从 Production V2 事实声明获取详情；没有时回退到用户保存的行程投影，
     * 不得将旧 Qdrant 命中提升为事实。
     */
    @GetMapping("/{id}/facts")
    public Result<Map<String, Object>> getFacts(@PathVariable String id) {
        return attractionApplicationService.getFacts(id);
    }
}
