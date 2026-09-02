package com.ai.guide.domain.preferences.service;

/**
 * 偏好版本并发冲突异常 (HTTP 409)
 *
 * 所属领域：domain.preferences.service（用户偏好画像服务层）
 */
public class PreferenceConflictException extends RuntimeException {

    private final long expectedRevision;
    private final long currentRevision;

    public PreferenceConflictException(long expectedRevision, long currentRevision) {
        super("用户偏好已被其他设备更新，请刷新后重试");
        this.expectedRevision = expectedRevision;
        this.currentRevision = currentRevision;
    }

    public long getExpectedRevision() {
        return expectedRevision;
    }

    public long getCurrentRevision() {
        return currentRevision;
    }
}
