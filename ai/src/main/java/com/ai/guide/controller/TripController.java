package com.ai.guide.controller;

import com.ai.guide.config.UserContext;
import com.ai.guide.model.Result;
import com.ai.guide.service.TripPlanPdfService;
import com.ai.guide.service.TripPlanService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai/trips")
public class TripController {

    private final TripPlanService tripPlanService;
    private final TripPlanPdfService tripPlanPdfService;

    public TripController(TripPlanService tripPlanService, TripPlanPdfService tripPlanPdfService) {
        this.tripPlanService = tripPlanService;
        this.tripPlanPdfService = tripPlanPdfService;
    }

    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        return Result.success("查询成功", tripPlanService.list(UserContext.getUserId()));
    }

    @PostMapping
    public Result<Map<String, Object>> save(@RequestBody Map<String, Object> plan) {
        if (UserContext.isAnonymous()) return Result.error(401, "请先登录后保存行程");
        return Result.success("行程已保存", tripPlanService.save(UserContext.getUserId(), plan));
    }

    @GetMapping("/{tripId}")
    public Result<Map<String, Object>> get(@PathVariable String tripId) {
        Map<String, Object> plan = tripPlanService.get(UserContext.getUserId(), tripId);
        return plan == null ? Result.error(404, "行程不存在") : Result.success("查询成功", plan);
    }

    @DeleteMapping("/{tripId}")
    public Result<Void> delete(@PathVariable String tripId) {
        if (UserContext.isAnonymous()) return Result.error(401, "请先登录后删除行程");
        return tripPlanService.delete(UserContext.getUserId(), tripId)
                ? Result.success("行程已删除", null)
                : Result.error(404, "行程不存在");
    }

    @PostMapping("/{tripId}/replan")
    public Result<Map<String, Object>> replan(@PathVariable String tripId,
                                               @RequestBody(required = false) Map<String, Object> request) {
        if (UserContext.isAnonymous()) return Result.error(401, "请先登录后局部重规划");
        Map<String, Object> plan = tripPlanService.replan(UserContext.getUserId(), tripId, request);
        return plan == null ? Result.error(404, "行程不存在") : Result.success("局部重规划已保存", plan);
    }

    @GetMapping(value = "/{tripId}/export", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> export(@PathVariable String tripId) {
        Map<String, Object> plan = tripPlanService.get(UserContext.getUserId(), tripId);
        if (plan == null) return ResponseEntity.notFound().build();
        byte[] pdf = tripPlanPdfService.export(plan);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=trip-" + tripId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
