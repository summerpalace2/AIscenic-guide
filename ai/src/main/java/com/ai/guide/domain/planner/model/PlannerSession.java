package com.ai.guide.domain.planner.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 规划会话聚合根模型
 *
 * 所属领域：domain.planner.model（智能排程规划领域模型）
 * 架构职责：承载进行中的规划会话核心数据（包含 sessionId、访问 Token、草稿行程快照、当前版本号及偏好快照）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlannerSession {
    private String sessionId;
    private String ownerType;
    private String ownerId;
    private int currentVersion;
    private int syncRevision;
    private Map<String, Object> trip = new LinkedHashMap<>();
    private Map<String, Object> constraints = new LinkedHashMap<>();
    private AppliedPreferencesSnapshot appliedPreferences = AppliedPreferencesSnapshot.empty();
    private String sessionAccessToken;
}
