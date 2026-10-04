package com.ai.guide.adapter.trip.web;

import com.ai.guide.application.trip.TripApplicationService;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.trip.model.Trip;
import com.ai.guide.domain.trip.model.TripVersion;
import com.ai.guide.domain.trip.service.TripService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 正式行程生命周期与版本演进适配器控制器 (Trip Controller)
 *
 * 架构定位：COLA 适配层 (Adapter Layer) - Web 协议入口。
 * 核心职责：作为正式行程生命周期的外部 HTTP 访问入口，负责入参协议解析、多租户上下文鉴权、
 * 委派应用服务编排 {@link TripApplicationService}，并精确输出快照详情、版本演进追踪、局部重规划元数据与 PDF 手册流。
 *
 * 核心对外接口：
 * 1. {@link #list}: 查询当前登录用户的全部已保存行程。
 * 2. {@link #create}: 保存新行程（支持请求体 idempotencyKey 幂等校验与版本 1 初始化）。
 * 3. {@link #get}: 获取指定行程的最新版本快照详情（严格鉴权，禁止越权读取）。
 * 4. {@link #update}: 更新行程内容并自动递增版本号 (version + 1)。
 * 5. {@link #delete}: 物理清理行程及附属版本历史。
 * 6. {@link #versions}: 获取指定行程的全量历史版本列表与变更原因追踪。
 * 7. {@link #version}: 获取指定版本号的历史快照。
 * 8. {@link #replan}: 局部重规划替换指定景点。
 * 9. {@link #export}: 导出符合排版标准的高清 PDF 行程手册。
 */
@RestController
@RequestMapping("/ai/trips")
public class TripController {

    private final TripApplicationService tripApplicationService;

    public TripController(TripApplicationService tripApplicationService) {
        this.tripApplicationService = tripApplicationService;
    }

    @GetMapping
    public ResponseEntity<?> list() {
        if (UserContext.isAnonymous()) return error(401, "请先登录后查询行程", Map.of());
        return ResponseEntity.ok(Result.success("查询成功", tripApplicationService.listTrips(UserContext.getUserId())));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> request) {
        if (UserContext.isAnonymous()) return error(401, "请先登录后保存行程", Map.of());
        return response(tripApplicationService.createTrip(UserContext.getUserId(), request), "行程已保存");
    }

    @GetMapping("/{tripId}")
    public ResponseEntity<?> get(@PathVariable String tripId) {
        if (UserContext.isAnonymous()) return error(401, "请先登录后查询行程", Map.of());
        Trip trip = tripApplicationService.getTrip(UserContext.getUserId(), tripId);
        return trip == null
                ? error(404, "行程不存在", Map.of())
                : ResponseEntity.ok(Result.success("查询成功", trip));
    }

    @PutMapping("/{tripId}")
    public ResponseEntity<?> update(@PathVariable String tripId,
                                    @RequestBody(required = false) Map<String, Object> request) {
        if (UserContext.isAnonymous()) return error(401, "请先登录后更新行程", Map.of());
        return response(tripApplicationService.updateTrip(UserContext.getUserId(), tripId, request), "行程已更新");
    }

    /**
     * DELETE is intentionally physical to match the existing Node and Java behavior:
     * Trip, all immutable versions, and idempotency records are removed; there is no
     * restore/recycle-bin contract. It requires caller's current version so stale clients
     * cannot delete newer work.
     */
    @DeleteMapping("/{tripId}")
    public ResponseEntity<?> delete(@PathVariable String tripId,
                                    @RequestBody(required = false) Map<String, Object> request) {
        if (UserContext.isAnonymous()) return error(401, "请先登录后删除行程", Map.of());
        return response(tripApplicationService.deleteTrip(UserContext.getUserId(), tripId, request), "行程已删除");
    }

    @GetMapping("/{tripId}/versions")
    public ResponseEntity<?> versions(@PathVariable String tripId) {
        if (UserContext.isAnonymous()) return error(401, "请先登录后查询行程版本", Map.of());
        List<TripVersion> versions = tripApplicationService.getVersions(UserContext.getUserId(), tripId);
        return versions == null
                ? error(404, "行程不存在", Map.of())
                : ResponseEntity.ok(Result.success("查询成功", versions));
    }

    @GetMapping("/{tripId}/versions/{version}")
    public ResponseEntity<?> version(@PathVariable String tripId, @PathVariable int version) {
        if (UserContext.isAnonymous()) return error(401, "请先登录后查询行程版本", Map.of());
        TripVersion result = tripApplicationService.getVersion(UserContext.getUserId(), tripId, version);
        return result == null
                ? error(404, "行程版本不存在", Map.of())
                : ResponseEntity.ok(Result.success("查询成功", result));
    }

    @PostMapping("/{tripId}/replan")
    public ResponseEntity<?> replan(@PathVariable String tripId,
                                    @RequestBody(required = false) Map<String, Object> request) {
        if (UserContext.isAnonymous()) return error(401, "请先登录后局部重规划", Map.of());
        return response(tripApplicationService.replanTrip(UserContext.getUserId(), tripId, request), "局部重规划已保存");
    }

    @GetMapping(value = "/{tripId}/export", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> export(@PathVariable String tripId) {
        if (UserContext.isAnonymous()) return ResponseEntity.status(401).build();
        TripApplicationService.PdfExportResult exportResult = tripApplicationService.exportTripPdf(UserContext.getUserId(), tripId);
        if (exportResult == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(pdfFilename(exportResult.title()), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.APPLICATION_PDF)
                .body(exportResult.pdfBytes());
    }

    private String pdfFilename(String title) {
        String safeTitle = (title == null ? "重庆旅行行程" : title)
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (safeTitle.isBlank()) safeTitle = "重庆旅行行程";
        return safeTitle.substring(0, Math.min(safeTitle.length(), 80)) + "-行程手册.pdf";
    }

    private ResponseEntity<?> response(TripService.OperationResult result, String fallbackMessage) {
        if (result.status() >= 400) {
            Map<String, Object> data = new LinkedHashMap<>();
            if (result.tripId() != null) data.put("tripId", result.tripId());
            if (result.expectedVersion() != null) data.put("expectedVersion", result.expectedVersion());
            if (result.currentVersion() != null) data.put("currentVersion", result.currentVersion());
            if (result.operationStatus() != null) data.put("operationStatus", result.operationStatus());
            return error(result.status(), result.message(), data);
        }
        String message = result.message() == null || result.message().isBlank()
                ? fallbackMessage : result.message();
        return ResponseEntity.status(result.status())
                .body(Result.success(message, successData(result)));
    }

    /**
     * Preserve the Trip payload for ordinary create/update responses. Replan
     * responses additionally expose server-selected replacement metadata so a
     * client never has to infer the mutation result from the snapshot alone.
     */
    private Object successData(TripService.OperationResult result) {
        if (result.replacementVenueId() == null && result.changedSegments().isEmpty()) {
            return result.trip();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("trip", result.trip());
        data.put("tripId", result.tripId());
        data.put("currentVersion", result.currentVersion());
        data.put("operationStatus", result.operationStatus());
        data.put("replacementVenueId", result.replacementVenueId());
        data.put("changedSegments", result.changedSegments());
        return data;
    }

    private ResponseEntity<Result<Map<String, Object>>> error(int status, String message,
                                                               Map<String, Object> data) {
        return ResponseEntity.status(status)
                .body(new Result<>(status, message, data, false));
    }
}
