package com.ai.guide.application.trip;

import com.ai.guide.domain.trip.gateway.TripPlanPdfExportPort;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import com.ai.guide.domain.trip.service.TripService;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 正式行程应用服务 (Trip Application Service)
 *
 * 架构定位：COLA 应用层 (Application Layer) - 用例编排中心。
 * 核心职责：统筹行程创建、修改递增版本、幂等重放、版本快照追溯、局部重规划与手册 PDF 导出等核心用例，
 * 隔离外部 HTTP 协议与内部领域业务规则。
 */
@Service
public class TripApplicationService {

    private final TripService tripService;
    private final TripPlanPdfExportPort tripPlanPdfExportPort;

    public TripApplicationService(TripService tripService,
                                  TripPlanPdfExportPort tripPlanPdfExportPort) {
        this.tripService = tripService;
        this.tripPlanPdfExportPort = tripPlanPdfExportPort;
    }

    /**
     * 查询指定用户的全部正式行程列表
     */
    public List<Trip> listTrips(String ownerId) {
        return tripService.list(ownerId);
    }

    /**
     * 查询单条正式行程详情快照
     */
    public Trip getTrip(String ownerId, String tripId) {
        return tripService.get(ownerId, tripId);
    }

    /**
     * 保存新行程（支持请求体 idempotencyKey 幂等校验与版本 1 初始化）
     */
    public TripService.OperationResult createTrip(String ownerId, Map<String, Object> request) {
        return tripService.create(ownerId, request);
    }

    /**
     * 更新行程内容并自动递增版本号 (version + 1)
     */
    public TripService.OperationResult updateTrip(String ownerId, String tripId, Map<String, Object> request) {
        return tripService.update(ownerId, tripId, request);
    }

    /**
     * 物理清理行程及附属版本与幂等历史
     */
    public TripService.OperationResult deleteTrip(String ownerId, String tripId, Map<String, Object> request) {
        return tripService.delete(ownerId, tripId, request);
    }

    /**
     * 查询指定行程的全量历史版本列表
     */
    public List<TripVersion> getVersions(String ownerId, String tripId) {
        return tripService.versions(ownerId, tripId);
    }

    /**
     * 查询指定版本号的行程历史快照
     */
    public TripVersion getVersion(String ownerId, String tripId, int version) {
        return tripService.version(ownerId, tripId, version);
    }

    /**
     * 局部重规划行程
     */
    public TripService.OperationResult replanTrip(String ownerId, String tripId, Map<String, Object> request) {
        return tripService.replan(ownerId, tripId, request);
    }

    /**
     * 导出高清行程手册 PDF 字节流
     */
    public PdfExportResult exportTripPdf(String ownerId, String tripId) {
        Trip trip = tripService.get(ownerId, tripId);
        if (trip == null) {
            return null;
        }
        Map<String, Object> exportPlan = new LinkedHashMap<>(trip.plan());
        // Historical snapshots may predate title synchronization.
        exportPlan.put("title", trip.title());
        exportPlan.put("tripId", trip.id());
        exportPlan.put("currentVersion", trip.currentVersion());
        exportPlan.put("tripCreatedAt", trip.createdAt());
        exportPlan.put("tripUpdatedAt", trip.updatedAt());
        byte[] pdf = tripPlanPdfExportPort.export(exportPlan);
        return new PdfExportResult(trip.title(), pdf);
    }

    /**
     * PDF 导出结果值对象
     */
    public record PdfExportResult(String title, byte[] pdfBytes) {
    }
}
