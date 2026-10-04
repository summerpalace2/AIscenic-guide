package com.ai.guide.domain.preferences.gateway;

import com.ai.guide.domain.preferences.model.UserPreferences;
import java.util.Optional;

/**
 * 用户偏好主数据仓储标准端口契约接口
 *
 * 所属领域：domain.preferences.gateway（用户偏好画像端口层）
 * 架构职责：定义偏好主数据持久化的标准操作，倒置依赖以保持领域层纯粹性。
 */
public interface PreferencesRepositoryPort {

    /**
     * 根据用户 ID 查询偏好记录
     */
    Optional<UserPreferences> findByUserId(String userId);

    /**
     * 插入新的用户偏好记录
     */
    void insert(UserPreferences preferences);

    /**
     * 基于期望版本号执行 CAS 乐观并发更新
     *
     * @param next             新版本偏好数据
     * @param expectedRevision 期望的旧版本号
     * @return 更新成功返回 true，否则 false
     */
    boolean updateCas(UserPreferences next, long expectedRevision);
}
