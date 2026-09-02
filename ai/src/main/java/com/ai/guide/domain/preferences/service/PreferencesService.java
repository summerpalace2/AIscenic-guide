package com.ai.guide.domain.preferences.service;

import com.ai.guide.domain.preferences.api.UserPreferencesPatch;
import com.ai.guide.domain.preferences.model.PreferencesSchema;
import com.ai.guide.domain.preferences.model.UserPreferences;
import com.ai.guide.domain.preferences.repository.PreferenceAuditRepository;
import com.ai.guide.domain.preferences.repository.PreferencesRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

/**
 * 用户个性化偏好画像权威管理服务
 *
 * 所属领域：domain.preferences（用户偏好画像域）
 * 架构职责：作为系统用户偏好的唯一权威写入与读取方（Source of Truth），提供强类型的字段校验、基于 Revision 的乐观并发控制 (OCC) 以及变更审计日志沉淀。
 *
 * 核心方法与职责：
 * 1. get：查询指定用户的偏好档案（若未创建则返回初始默认档案）
 *    - 参数：userId（当前用户 ID）
 *    - 返回值：UserPreferences 偏好实体
 * 2. merge：增量合并偏好字段（仅更新补丁中显式包含的非空字段）
 *    - 参数：userId、patch（增量偏好补丁）、expectedRevision（期望版本号）
 * 3. replace：全量替换用户偏好（未包含的字段重置为 UNSPECIFIED 默认值）
 * 4. clear：重置并清空所有偏好字段
 */
@Service
public class PreferencesService {

    private final PreferencesRepository repository;
    private final PreferenceAuditRepository auditRepository;

    public PreferencesService(PreferencesRepository repository,
                              PreferenceAuditRepository auditRepository) {
        this.repository = repository;
        this.auditRepository = auditRepository;
    }

    public UserPreferences get(String userId) {
        requireOwner(userId);
        return repository.findByUserId(userId)
                .orElseGet(() -> UserPreferences.empty(userId, 0, 0));
    }

    @Transactional
    public MutationResult merge(String userId, UserPreferencesPatch patch, Long expectedRevision) {
        return mutate(userId, patch == null ? UserPreferencesPatch.empty() : patch,
                expectedRevision, Operation.MERGE);
    }

    @Transactional
    public MutationResult replace(String userId, UserPreferencesPatch patch, Long expectedRevision) {
        return mutate(userId, patch == null ? UserPreferencesPatch.empty() : patch,
                expectedRevision, Operation.REPLACE);
    }

    @Transactional
    public MutationResult clear(String userId, Long expectedRevision) {
        return mutate(userId, UserPreferencesPatch.empty(), expectedRevision, Operation.CLEAR);
    }

    private MutationResult mutate(String userId, UserPreferencesPatch patch,
                                  Long expectedRevision, Operation operation) {
        requireOwner(userId);
        if (expectedRevision != null && expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision 不能为负数");
        }
        Long bodyRevision = patch.expectedRevision();
        long requestedRevision = expectedRevision != null
                ? expectedRevision
                : bodyRevision == null ? -1 : bodyRevision;

        Optional<UserPreferences> stored = repository.findByUserId(userId);
        UserPreferences current = stored.orElse(null);
        long currentRevision = current == null ? 0 : current.revision();
        if (requestedRevision >= 0 && requestedRevision != currentRevision) {
            throw new PreferenceConflictException(requestedRevision, currentRevision);
        }

        long now = System.currentTimeMillis();
        long createdAt = current == null ? now : current.createdAt();
        UserPreferences base = current == null ? UserPreferences.empty(userId, 0, 0) : current;
        UserPreferences values = switch (operation) {
            case MERGE -> patch.mergeOnto(base);
            case REPLACE, CLEAR -> operation == Operation.CLEAR
                    ? UserPreferences.empty(userId, currentRevision, createdAt)
                    : patch.replaceOnto(userId, currentRevision, createdAt, now);
        };
        UserPreferences next = new UserPreferences(
                userId,
                PreferencesSchema.CURRENT_SCHEMA_VERSION,
                currentRevision + 1,
                values.interests(),
                values.walkingTolerance(),
                values.budget(),
                values.companions(),
                values.transportPreference(),
                values.dietPreference(),
                values.stayArea(),
                values.legacyMetadata(),
                createdAt,
                now);

        try {
            if (current == null) {
                repository.insert(next);
            } else if (!repository.updateCas(next, currentRevision)) {
                throw conflictAfterCasFailure(userId, currentRevision);
            }
        } catch (DuplicateKeyException duplicate) {
            throw conflictAfterCasFailure(userId, currentRevision);
        }
        auditRepository.record(userId, current, next, operation.name());
        return new MutationResult(next, current == null);
    }

    private PreferenceConflictException conflictAfterCasFailure(String userId, long expectedRevision) {
        long currentRevision = repository.findByUserId(userId)
                .map(UserPreferences::revision).orElse(0L);
        return new PreferenceConflictException(expectedRevision, currentRevision);
    }

    private void requireOwner(String userId) {
        if (userId == null || userId.isBlank()
                || "anonymous".equalsIgnoreCase(userId)
                || "guest".equalsIgnoreCase(userId)
                || "null".equalsIgnoreCase(userId)) {
            throw new IllegalArgumentException("正式用户偏好必须绑定已认证 Java user");
        }
    }

    public record MutationResult(UserPreferences preferences, boolean created) {
    }

    private enum Operation {
        MERGE,
        REPLACE,
        CLEAR
    }
}
