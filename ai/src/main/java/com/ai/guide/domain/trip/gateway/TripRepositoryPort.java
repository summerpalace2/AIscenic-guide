package com.ai.guide.domain.trip.gateway;

import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import java.util.List;
import java.util.Map;

/**
 * 正式行程主表与版本表持久化仓储端口契约接口
 *
 * 所属领域：domain.trip.gateway（行程域端口层）
 * 架构职责：定义行程聚合根、快照版本与幂等性控制的标准持久化契约，实现技术实现与核心业务的依赖倒置。
 */
public interface TripRepositoryPort {

    /**
     * 查询指定拥有者的所有正式行程列表（按更新时间倒序）
     */
    List<Trip> list(String ownerId);

    /**
     * 查询指定拥有者的单条正式行程聚合详情
     */
    Trip find(String ownerId, String tripId);

    /**
     * 查询指定版本号的行程历史不可变快照
     */
    TripVersion findVersion(String ownerId, String tripId, int versionNumber);

    /**
     * 查询指定行程的全部历史版本快照列表（按版本升序）
     */
    List<TripVersion> listVersions(String ownerId, String tripId);

    /**
     * 原子创建行程聚合记录并初始化版本 1 快照
     */
    void insertTripAndVersion(String tripId, String ownerId, String title, String status,
                              Map<String, Object> snapshot, long now, String createdBy,
                              String versionId);

    /**
     * 插入新的不可变行程版本快照
     */
    void insertVersion(String versionId, String tripId, int versionNumber,
                       Map<String, Object> snapshot, String changeReason,
                       long now, String createdBy);

    /**
     * CAS 原子递增当前行程版本指针与标题
     */
    int advanceCurrentVersion(String ownerId, String tripId, int expectedVersion,
                              int nextVersion, String title, long now);

    /**
     * 单独修改行程标题
     */
    int updateTitle(String ownerId, String tripId, String title, long now);

    /**
     * 仅当拥有者版本匹配时物理清理行程、版本历史及幂等性记录
     */
    int deleteIfCurrentVersion(String ownerId, String tripId, int expectedVersion);

    /**
     * 查询幂等提交记录
     */
    IdempotencyRecord findIdempotency(String ownerId, String operation, String key);

    /**
     * 预占/锁定幂等键
     */
    void reserveIdempotency(String ownerId, String operation, String key,
                            String fingerprint, String tripId, long now);

    /**
     * 提交成功后完成幂等记录落盘
     */
    void completeIdempotency(String ownerId, String operation, String key, Trip trip);

    /**
     * 业务失败时释放幂等预占键
     */
    void deleteIdempotency(String ownerId, String operation, String key);

    /**
     * 辅助方法：直接插入完整幂等记录
     */
    void insertIdempotency(String ownerId, String operation, String key,
                           String fingerprint, Trip trip, long now);

    /**
     * 反序列化幂等响应快照
     */
    Trip deserializeIdempotentTrip(IdempotencyRecord record);

    /**
     * 幂等提交快照记录值对象
     */
    record IdempotencyRecord(String ownerId, String operation, String key,
                             String requestFingerprint, String tripId,
                             int versionNumber, String responseJson, long createdAt) {
    }
}
