package com.ai.guide.domain.preferences.gateway;

import com.ai.guide.domain.preferences.model.UserPreferences;

/**
 * 用户偏好变更审计日志仓储端口契约接口
 *
 * 所属领域：domain.preferences.gateway（用户偏好画像端口层）
 * 架构职责：定义偏好审计日志记录操作，隔离审计持久化具体技术。
 */
public interface PreferenceAuditRepositoryPort {

    /**
     * 记录偏好变更审计日志
     *
     * @param userId    用户 ID
     * @param before    变更前偏好快照
     * @param after     变更后偏好快照
     * @param operation 操作类型 (MERGE, REPLACE, CLEAR)
     */
    void record(String userId, UserPreferences before, UserPreferences after, String operation);
}
