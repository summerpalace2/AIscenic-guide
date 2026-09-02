package com.ai.guide.domain.planner.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 确认并应用调整方案请求 DTO
 *
 * 所属领域：domain.planner.api（智能排程规划 API 层）
 * 架构职责：封装用户在前端确认应用某个调整选项时的请求参数（包含 proposalId、选中的 optionId 及 baseRevision 乐观锁版本号）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApplyAdjustmentRequestDto {
    private String proposalId;
    private String optionId;
    @Builder.Default
    private int baseRevision = 1;
    /** 用户明确选择“仍按此方案”时允许应用带有提示的直接替换。 */
    @Builder.Default
    private boolean forceApply = false;
    @Builder.Default
    private String sessionAccessToken = "";
    @Builder.Default
    private String idempotencyKey = "";
}
