package com.ai.guide.domain.planner.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.List;

/**
 * 规划详情页前端交互上下文模型
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 * 架构职责：传递前端当前聚焦的天数 (activeDay)、单选兼容目标、多个选中卡片、激活的提案及固定钉住的站点。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanPageContext {
    private Integer activeDay;
    private String selectedStopId;
    @Builder.Default
    private List<String> selectedStopIds = new ArrayList<>();
    private String activeProposalId;
    @Builder.Default
    private List<String> pinnedStopIds = new ArrayList<>();
}
