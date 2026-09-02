package com.ai.guide.domain.planner.api;

import com.ai.guide.domain.planner.model.PlannerSession;
import com.ai.guide.domain.trip.model.Trip;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 影子测试站点变更请求 DTO
 *
 * 所属领域：domain.planner.api（智能排程规划 API 层）
 * 架构职责：用于服务端影子比对与无副作用测试的站点变更入参载体。
 */
@Data
@NoArgsConstructor
public class ShadowMutationRequest {
    private Map<String, Object> trip = new LinkedHashMap<>();
    private String targetStopId = "";
    private String candidateVenueId = "";
    private String reason = "";
    private String operation = "";
    private String attractionId = "";
    private Integer day;
    private String stopId = "";
}
