package com.ai.guide.domain.planner.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行程站点原子变更请求 DTO
 *
 * 所属领域：domain.planner.api（智能排程规划 API 层）
 * 架构职责：封装对规划草稿站点的增、删、改、替换原子操作参数（包含 operation、attractionId、dayNumber、targetStopId）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlannerMutationRequest {
    private String targetStopId = "";
    private String candidateVenueId = "";
    private String reason = "";
    private String operation = "";
    private String attractionId = "";
    private Integer day;
    private String stopId = "";
    private Integer expectedVersion;
    private String sessionAccessToken = "";
}
