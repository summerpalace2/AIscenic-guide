package com.ai.guide.domain.planner.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行程站点原子变更请求命令对象
 *
 * 架构定位：COLA 领域模型 (Domain Model / Command)
 * 职责：封装对规划草稿站点的增、删、改、替换原子操作参数（包含 operation、attractionId、dayNumber、targetStopId）。
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
    private boolean allowRepeat;
}
