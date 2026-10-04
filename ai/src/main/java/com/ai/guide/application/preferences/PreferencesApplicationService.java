package com.ai.guide.application.preferences;

import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.model.UserPreferencesPatch;
import com.ai.guide.domain.preferences.service.PreferencesService;
import org.springframework.stereotype.Service;

/**
 * 用户个性化偏好应用服务 (Preferences Application Service)
 *
 * 架构定位：COLA 应用层 (Application Layer) - 用例编排中心。
 * 核心职责：负责统一编排用户个性化偏好画像的核心用例（查询、增量合并、全量替换与重置清空），
 * 作为适配层与领域层之间的防腐与调度中枢。
 */
@Service
public class PreferencesApplicationService {

    private final PreferencesService preferencesService;

    public PreferencesApplicationService(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    /**
     * 查询指定用户的个性化偏好画像
     */
    public UserPreferences getPreferences(String userId) {
        return preferencesService.get(userId);
    }

    /**
     * 增量合并更新偏好画像
     */
    public PreferencesService.MutationResult mergePreferences(String userId, UserPreferencesPatch patch, Long expectedRevision) {
        return preferencesService.merge(userId, patch, expectedRevision);
    }

    /**
     * 全量替换更新偏好画像
     */
    public PreferencesService.MutationResult replacePreferences(String userId, UserPreferencesPatch patch, Long expectedRevision) {
        return preferencesService.replace(userId, patch, expectedRevision);
    }

    /**
     * 重置清空用户偏好画像
     */
    public PreferencesService.MutationResult clearPreferences(String userId, Long expectedRevision) {
        return preferencesService.clear(userId, expectedRevision);
    }
}
