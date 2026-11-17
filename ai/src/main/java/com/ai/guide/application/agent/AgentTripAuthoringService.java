package com.ai.guide.application.agent;

import com.ai.guide.common.context.OperationDeadline;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.attraction.service.AttractionService;
import com.ai.guide.domain.dining.model.DiningVenue;
import com.ai.guide.domain.dining.service.DiningVenueService;
import com.ai.guide.domain.geo.gateway.GeoRouteGatewayPort;
import com.ai.guide.domain.geo.model.GeoCoordinate;
import com.ai.guide.domain.geo.model.GeoCoordinates;
import com.ai.guide.domain.geo.model.GeoOutcomeStatus;
import com.ai.guide.domain.geo.model.GeoPoiCandidate;
import com.ai.guide.domain.geo.model.GeoPoiOutcome;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.CreateResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.MutationResult;
import com.ai.guide.domain.planner.gateway.PlannerSessionRepositoryPort.StoredSession;
import com.ai.guide.domain.planner.gateway.RouteGatewayPort;
import com.ai.guide.domain.planner.model.AppliedPreferencesSnapshot;
import com.ai.guide.domain.planner.model.HydrationResult;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.PlannerPins;
import com.ai.guide.domain.planner.service.PlannerPreferencesResolver;
import com.ai.guide.domain.trip.service.TripPlanLimits;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 全智能体原生行程编排与领域校核服务。
 *
 * <p>架构定位：COLA 应用层（application.agent）。
 * 彻底解放智能体按天、按站自主编排行程与餐饮的自由度，同时在 Java 侧守住确定性护栏：
 * <ol>
 *   <li>POI 实景核验（Grounding Gate）：结合本地受控目录与高德实时 POI 核实经纬度，绝不擅自替换为无关景点；</li>
 *   <li>实时高德路网与天气水化：通过 {@link RouteGatewayPort#hydrate} 补齐真实通勤耗时、路线与天气预报；</li>
 *   <li>物理边界与写保护：校验 {@link TripPlanLimits}、维护 {@link PlannerPins} 已固定站点与 CAS 版本号；</li>
 *   <li>沙箱预览与 HIL 确认分离：{@link #previewPatch} 仅在内存中生成差异提案，{@link #applyPreviewedPatch} 经人工确认后提交持久化。</li>
 * </ol>
 */
@Service
public class AgentTripAuthoringService {

    private static final Logger log = LoggerFactory.getLogger(AgentTripAuthoringService.class);
    private static final long HYDRATION_TIMEOUT_MS = 25_000L;
    private static final long PROPOSAL_TTL_MILLIS = Duration.ofMinutes(15).toMillis();
    private static final int MAX_PENDING_PROPOSALS = 512;

    private final PlannerSessionRepositoryPort sessionRepository;
    private final RouteGatewayPort routeGateway;
    private final GeoRouteGatewayPort geoRouteGateway;
    private final AttractionService attractionService;
    private final DiningVenueService diningVenueService;
    private final PlannerPreferencesResolver preferencesResolver;
    private final TripPlanLimits planLimits;
    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, PendingPatchProposal> pendingProposals = new ConcurrentHashMap<>();

    @Autowired
    public AgentTripAuthoringService(
            PlannerSessionRepositoryPort sessionRepository,
            @Autowired(required = false) RouteGatewayPort routeGateway,
            @Autowired(required = false) GeoRouteGatewayPort geoRouteGateway,
            @Autowired(required = false) AttractionService attractionService,
            @Autowired(required = false) DiningVenueService diningVenueService,
            @Autowired(required = false) PlannerPreferencesResolver preferencesResolver,
            @Autowired(required = false) TripPlanLimits planLimits,
            ObjectMapper objectMapper) {
        this.sessionRepository = Objects.requireNonNull(sessionRepository, "sessionRepository 不能为空");
        this.routeGateway = routeGateway;
        this.geoRouteGateway = geoRouteGateway;
        this.attractionService = attractionService;
        this.diningVenueService = diningVenueService;
        this.preferencesResolver = preferencesResolver;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.planLimits = planLimits == null ? new TripPlanLimits(this.objectMapper) : planLimits;
    }

    /** 建草案执行回执 */
    public record CreateDraftResult(boolean ok, String errorCode, String message,
                                    String sessionId, int currentVersion,
                                    Map<String, Object> trip, List<String> unverifiedStops) {
        static CreateDraftResult fail(String code, String msg, List<String> unverified) {
            return new CreateDraftResult(false, code, msg, "", 0, Map.of(), unverified == null ? List.of() : unverified);
        }
    }

    /** 局部/跨天修改预演回执 */
    public record PreviewPatchResult(boolean ok, String errorCode, String message,
                                     String proposalId, int baseRevision,
                                     List<Map<String, Object>> stopChanges, List<String> changedSegments) {
        static PreviewPatchResult fail(String code, String msg) {
            return new PreviewPatchResult(false, code, msg, "", 0, List.of(), List.of());
        }
    }

    /** HIL 确认应用补丁回执 */
    public record ApplyPatchResult(boolean ok, String errorCode, String message,
                                   String sessionId, int currentVersion,
                                   Map<String, Object> trip, List<String> changedSegments) {
        static ApplyPatchResult fail(String code, String msg) {
            return new ApplyPatchResult(false, code, msg, "", 0, Map.of(), List.of());
        }
    }

    private record PendingPatchProposal(
            String proposalId,
            String ownerUserId,
            String sessionId,
            int baseRevision,
            String changeSummary,
            Map<String, Object> candidateTrip,
            List<String> changedSegments,
            long expiresAtMillis
    ) {}

    /**
     * 将智能体从零编排的多天结构化行程草案经过 POI 核验与高德水化后落库为规划会话。
     */
    public CreateDraftResult createDraft(AgentExecutionContext ctx, JsonNode args) {
        if (ctx == null || ctx.anonymous() || ctx.userId().isBlank()) {
            return CreateDraftResult.fail("LOGIN_REQUIRED",
                    "生成并保存专属行程需要先登录账号，请引导用户登录后再发起规划。", List.of());
        }
        if (args == null || !args.has("days") || !args.get("days").isArray() || args.get("days").isEmpty()) {
            return CreateDraftResult.fail("MISSING_DAYS",
                    "请先按天编排具体行程 days 数组（每天至少包含 1 个景点、餐饮或自由时段），再调用本工具落库。", List.of());
        }

        String city = firstNonBlank(text(args, "city", ""), ctx.activeCity(), "重庆");
        JsonNode rawDays = args.get("days");
        if (rawDays.size() > 7) {
            return CreateDraftResult.fail("TOO_MANY_DAYS", "单次规划最多支持 7 天行程，请精简天数。", List.of());
        }

        List<String> unverifiedStops = new ArrayList<>();
        List<Map<String, Object>> normalizedDays = normalizeDays(rawDays, city, Map.of(), unverifiedStops);
        if (!unverifiedStops.isEmpty()) {
            return CreateDraftResult.fail("UNVERIFIED_STOP_LOCATION",
                    "以下站点未能匹配到真实地理坐标：" + String.join("、", unverifiedStops)
                            + "。请先调用 search_poi_and_dining 检索确认真实 POI 名称与坐标后再提交。",
                    unverifiedStops);
        }

        Map<String, Object> constraintsMap = buildConstraintsMap(args, city, normalizedDays.size());
        AppliedPreferencesSnapshot appliedSnapshot = resolvePreferencesSnapshot(city, constraintsMap);

        String title = firstNonBlank(text(args, "title", ""), city + normalizedDays.size() + "天·智能体专属定制");
        String subtitle = firstNonBlank(text(args, "subtitle", ""), buildDefaultSubtitle(constraintsMap));
        String summary = firstNonBlank(text(args, "summary", ""), "由「渝游智策」智能体结合实时天气、POI 与路网为您按需编排。");

        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("title", title);
        trip.put("subtitle", subtitle);
        trip.put("city", city);
        trip.put("version", 1);
        trip.put("summary", summary);
        trip.put("planMode", "AGENT_NATIVE");
        trip.put("authoringEngine", "FULL_AGENT");
        trip.put("constraints", constraintsMap);
        trip.put("appliedPreferences", appliedSnapshot.asMap());
        trip.put("pinnedStopIds", List.of());
        trip.put("days", normalizedDays);

        String limitError = validateLimits(trip);
        if (limitError != null) {
            return CreateDraftResult.fail("PLAN_LIMITS_EXCEEDED", limitError, List.of());
        }

        hydrateSafely(trip);
        refreshTripEnvelopeMetrics(trip, 1, "智能体初始编排", summary, List.of());

        String promptSummary = firstNonBlank(text(args, "prompt", ""), title);
        String idempotencyKey = "agent-native-" + UUID.randomUUID();
        String fingerprint = sha256(ctx.userId() + ":" + idempotencyKey + ":" + title);
        CreateResult created = sessionRepository.create(
                "USER", ctx.userId(), promptSummary, constraintsMap, appliedSnapshot,
                trip, idempotencyKey, fingerprint
        );
        if (created == null || created.session() == null || "CONFLICT".equals(created.status())) {
            return CreateDraftResult.fail("CREATE_SESSION_FAILED", "规划会话持久化失败，请重试。", List.of());
        }
        return new CreateDraftResult(true, "", "已创建全智能体定制行程草案",
                created.session().sessionId(), created.session().currentVersion(),
                created.session().trip(), List.of());
    }

    /**
     * 在只读沙箱中预演智能体提出的任意跨天/单站/餐饮修改，生成差异摘要并返回 proposalId（不改写原草稿）。
     */
    public PreviewPatchResult previewPatch(AgentExecutionContext ctx, JsonNode args) {
        if (ctx == null || !ctx.hasPlannerSession()) {
            return PreviewPatchResult.fail("NO_PLANNER_SESSION", "当前未绑定可修改的行程草稿，请先生成或打开一份行程。");
        }
        boolean hasOperations = args != null && args.has("operations") && args.get("operations").isArray() && !args.get("operations").isEmpty();
        boolean hasDays = args != null && args.has("days") && args.get("days").isArray() && !args.get("days").isEmpty();
        if (!hasOperations && !hasDays) {
            return PreviewPatchResult.fail("MISSING_DAYS",
                    "请传入局部补丁 operations（推荐，用于单站替换/增删/移动）或修改后的 days 数组再发起预览。");
        }

        StoredSession session = loadAuthorizedSession(ctx);
        if (session == null) {
            return PreviewPatchResult.fail("SESSION_NOT_FOUND", "当前规划会话不存在或无权访问，请重新打开行程。");
        }
        int expectedVersion = args.hasNonNull("baseRevision")
                ? args.get("baseRevision").asInt(session.currentVersion())
                : session.currentVersion();
        if (expectedVersion != session.currentVersion()) {
            return PreviewPatchResult.fail("REVISION_CONFLICT",
                    "当前行程已更新至 v" + session.currentVersion() + "（传入基线 v" + expectedVersion + "），请先重新读取最新草案后再调整。");
        }

        Map<String, Object> beforeTrip = copyMap(session.trip());
        String city = firstNonBlank(text(args, "city", ""), safeText(beforeTrip.get("city")), ctx.activeCity(), "重庆");
        Map<String, Map<String, Object>> existingStopsByKey = indexExistingStops(beforeTrip);

        List<String> unverifiedStops = new ArrayList<>();
        List<Map<String, Object>> normalizedDays;
        if (hasOperations) {
            SurgicalPatchOutcome patchOutcome = applySurgicalOperations(beforeTrip, args.get("operations"), city, unverifiedStops);
            if (!patchOutcome.ok()) {
                return PreviewPatchResult.fail(patchOutcome.errorCode(), patchOutcome.message());
            }
            normalizedDays = patchOutcome.days();
        } else {
            normalizedDays = normalizeDays(args.get("days"), city, existingStopsByKey, unverifiedStops);
        }
        if (!unverifiedStops.isEmpty()) {
            return PreviewPatchResult.fail("UNVERIFIED_STOP_LOCATION",
                    "以下新增/修改站点未能核验真实坐标：" + String.join("、", unverifiedStops)
                            + "。请先调用 search_poi_and_dining 核实真实地点。");
        }

        Map<String, Object> candidateTrip = copyMap(beforeTrip);
        if (args.hasNonNull("title") && !text(args, "title", "").isBlank()) {
            candidateTrip.put("title", text(args, "title", ""));
        }
        if (args.hasNonNull("subtitle") && !text(args, "subtitle", "").isBlank()) {
            candidateTrip.put("subtitle", text(args, "subtitle", ""));
        }
        if (args.has("constraints") && args.get("constraints").isObject()) {
            Map<String, Object> merged = copyMap(beforeTrip.get("constraints"));
            args.get("constraints").fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) merged.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class));
            });
            merged.put("durationDays", normalizedDays.size());
            candidateTrip.put("constraints", merged);
        }
        candidateTrip.put("planMode", "AGENT_NATIVE");
        candidateTrip.put("authoringEngine", "FULL_AGENT");
        candidateTrip.put("days", normalizedDays);

        // 严格守卫已固定站点：不得移除、替换或私自改天
        if (!PlannerPins.retained(beforeTrip, candidateTrip)) {
            List<String> pinnedNames = resolvePinnedStopNames(beforeTrip);
            return PreviewPatchResult.fail("PINNED_STOP_PROTECTED",
                    "本次调整触及已固定（Pin）站点（" + String.join("、", pinnedNames)
                            + "）。若确实需要更换或移动已固定站点，请先征得用户同意并通过 confirm_update_pinned_stops 取消固定。");
        }
        PlannerPins.preserve(beforeTrip, candidateTrip);

        String limitError = validateLimits(candidateTrip);
        if (limitError != null) {
            return PreviewPatchResult.fail("PLAN_LIMITS_EXCEEDED", limitError);
        }

        hydrateSafely(candidateTrip);
        String changeSummary = firstNonBlank(text(args, "changeSummary", ""), "智能体按您的新要求编排调整方案");
        List<Map<String, Object>> stopChanges = computeStopChanges(beforeTrip, candidateTrip);
        List<String> changedSegments = collectChangedSegments(stopChanges);
        refreshTripEnvelopeMetrics(candidateTrip, session.currentVersion() + 1, "智能体动态调整", changeSummary, changedSegments);

        purgeExpiredProposals();
        String proposalId = "agent-prop-" + UUID.randomUUID();
        pendingProposals.put(proposalId, new PendingPatchProposal(
                proposalId,
                ctx.userId(),
                session.sessionId(),
                session.currentVersion(),
                changeSummary,
                candidateTrip,
                changedSegments,
                System.currentTimeMillis() + PROPOSAL_TTL_MILLIS
        ));

        return new PreviewPatchResult(true, "", changeSummary, proposalId,
                session.currentVersion(), stopChanges, changedSegments);
    }

    /**
     * 在用户明确批准 HIL 确认卡后，原子将预演候选快照写入规划会话（CAS 校验版本号 + 固定站点保护）。
     */
    public ApplyPatchResult applyPreviewedPatch(AgentExecutionContext ctx, String proposalId, Integer requestedBaseRevision) {
        if (ctx == null || !ctx.hasPlannerSession()) {
            return ApplyPatchResult.fail("NO_PLANNER_SESSION", "当前未绑定行程草稿，无法应用修改。");
        }
        if (proposalId == null || proposalId.isBlank()) {
            return ApplyPatchResult.fail("MISSING_PROPOSAL", "缺少调整提案编号 proposalId，请先生成预览。");
        }
        PendingPatchProposal proposal = pendingProposals.get(proposalId.trim());
        if (proposal == null) {
            return ApplyPatchResult.fail("PROPOSAL_NOT_FOUND", "调整提案已失效或已处理，请重新发起调整预览。");
        }
        if (System.currentTimeMillis() > proposal.expiresAtMillis()) {
            pendingProposals.remove(proposalId.trim());
            return ApplyPatchResult.fail("PROPOSAL_EXPIRED", "调整提案已超过 15 分钟有效期，请重新预览后再确认。");
        }
        if (!Objects.equals(proposal.ownerUserId(), ctx.userId())
                || !Objects.equals(proposal.sessionId(), ctx.plannerSessionId())) {
            return ApplyPatchResult.fail("FORBIDDEN", "当前调整提案不属于您的规划会话。");
        }

        StoredSession latestSession = loadAuthorizedSession(ctx);
        if (latestSession == null) {
            return ApplyPatchResult.fail("SESSION_NOT_FOUND", "规划会话不存在或已过期。");
        }
        int expectedVersion = requestedBaseRevision != null && requestedBaseRevision > 0
                ? requestedBaseRevision
                : proposal.baseRevision();
        if (latestSession.currentVersion() != expectedVersion || latestSession.currentVersion() != proposal.baseRevision()) {
            return ApplyPatchResult.fail("REVISION_CONFLICT",
                    "行程在您确认前已更新至 v" + latestSession.currentVersion() + "，请重新预览后再确认。");
        }
        if (!PlannerPins.retained(latestSession.trip(), proposal.candidateTrip())) {
            return ApplyPatchResult.fail("PINNED_STOP_PROTECTED", "行程固定站点状态已变化，无法应用旧提案。");
        }

        MutationResult updated = sessionRepository.update(
                latestSession,
                copyMap(proposal.candidateTrip()),
                expectedVersion,
                "AGENT_NATIVE_PATCH",
                "智能体调整",
                proposal.changeSummary(),
                proposal.changedSegments()
        );
        if (updated == null || "CONFLICT".equals(updated.status()) || updated.session() == null) {
            return ApplyPatchResult.fail("REVISION_CONFLICT", "版本并发冲突，请重新读取行程后再试。");
        }
        pendingProposals.remove(proposalId.trim());
        return new ApplyPatchResult(true, "",
                "已应用智能体调整方案（v" + updated.session().currentVersion() + "）：" + proposal.changeSummary(),
                updated.session().sessionId(),
                updated.session().currentVersion(),
                updated.session().trip(),
                proposal.changedSegments());
    }

    private StoredSession loadAuthorizedSession(AgentExecutionContext ctx) {
        String ownerType = ctx.anonymous() ? "ANONYMOUS" : "USER";
        String ownerId = ctx.anonymous() ? "" : ctx.userId();
        return sessionRepository.find(ctx.plannerSessionId(), ownerType, ownerId, ctx.plannerAccessToken());
    }

    private List<Map<String, Object>> normalizeDays(
            JsonNode rawDays,
            String city,
            Map<String, Map<String, Object>> existingStopsByKey,
            List<String> unverifiedStops) {
        List<Map<String, Object>> days = new ArrayList<>();
        Set<String> assignedIds = new HashSet<>();
        LocalDate baseDate = LocalDate.now();

        for (int i = 0; i < rawDays.size(); i++) {
            JsonNode dayNode = rawDays.get(i);
            int dayNum = dayNode.hasNonNull("day") ? Math.max(1, dayNode.get("day").asInt(i + 1)) : (i + 1);
            LocalDate dayDate = baseDate.plusDays(dayNum - 1L);
            String dateStr = firstNonBlank(text(dayNode, "date", ""), dayDate.toString());
            String theme = firstNonBlank(text(dayNode, "theme", ""), text(dayNode, "title", ""), "山城探索日");
            String dateLabel = firstNonBlank(text(dayNode, "dateLabel", ""), dateStr + " · 第" + dayNum + "天 · " + theme);

            Map<String, Object> dayMap = new LinkedHashMap<>();
            dayMap.put("day", dayNum);
            dayMap.put("date", dateStr);
            dayMap.put("theme", theme);
            dayMap.put("title", theme);
            dayMap.put("dateLabel", dateLabel);
            if (!text(dayNode, "agentNotes", "").isBlank()) {
                dayMap.put("agentNotes", text(dayNode, "agentNotes", ""));
            }

            List<Map<String, Object>> normalizedStops = new ArrayList<>();
            JsonNode rawStops = dayNode.path("stops");
            if (rawStops.isArray()) {
                for (int s = 0; s < rawStops.size(); s++) {
                    Map<String, Object> stop = normalizeStop(
                            rawStops.get(s), dayNum, s + 1, city, existingStopsByKey, assignedIds, unverifiedStops);
                    normalizedStops.add(stop);
                }
            }
            dayMap.put("stops", normalizedStops);
            days.add(dayMap);
        }
        return days;
    }

    private Map<String, Object> normalizeStop(
            JsonNode node,
            int dayNum,
            int stopOrder,
            String city,
            Map<String, Map<String, Object>> existingStopsByKey,
            Set<String> assignedIds,
            List<String> unverifiedStops) {
        String name = firstNonBlank(text(node, "name", ""), "行程节点 " + stopOrder);
        String explicitType = text(node, "type", "").toUpperCase(Locale.ROOT);
        String requestedId = firstNonBlank(text(node, "stopId", ""), text(node, "id", ""));

        Map<String, Object> inherited = findExistingStop(existingStopsByKey, requestedId, dayNum, name);

        String type = resolveStopType(explicitType, node, inherited);
        boolean isDining = "DINING".equals(type);
        boolean isFreeTime = "FREE_TIME".equals(type);

        // 本地目录匹配（景区或餐饮门店）
        Attraction catalogAttraction = isDining || isFreeTime ? null : matchCatalogAttraction(node, city, name, inherited);
        DiningVenue catalogDining = isDining ? matchCatalogDining(node, name) : null;

        String rawLocation = firstNonBlank(
                text(node, "location", ""),
                safeText(inherited.get("location")),
                catalogAttraction != null ? safeText(catalogAttraction.getLocation()) : "",
                catalogDining != null ? safeText(catalogDining.getLocation()) : ""
        );
        String amapPoiId = firstNonBlank(
                text(node, "poiId", ""),
                text(node, "amapPoiId", ""),
                safeText(inherited.get("amapPoiId"))
        );
        String address = firstNonBlank(
                text(node, "address", ""),
                safeText(inherited.get("address")),
                catalogAttraction != null ? safeText(catalogAttraction.getDistrict()) : "",
                catalogDining != null ? safeText(catalogDining.getDistrict()) : ""
        );
        String district = firstNonBlank(
                text(node, "district", ""),
                safeText(inherited.get("district")),
                catalogAttraction != null ? safeText(catalogAttraction.getDistrict()) : "",
                catalogDining != null ? safeText(catalogDining.getDistrict()) : "",
                city
        );
        String verifiedSource = firstNonBlank(text(node, "verifiedSource", ""), safeText(inherited.get("verifiedSource")));

        // Grounding Gate：无有效坐标且非自由时段时调用高德实时 POI 核验补全
        GeoCoordinate parsedCoord = GeoCoordinates.parse(rawLocation);
        if ((parsedCoord == null || !parsedCoord.isValid()) && !isFreeTime) {
            GeoPoiCandidate liveCandidate = resolveLivePoi(name, city, isDining);
            if (liveCandidate != null && liveCandidate.coordinate() != null && liveCandidate.coordinate().isValid()) {
                rawLocation = liveCandidate.coordinate().longitude() + "," + liveCandidate.coordinate().latitude();
                parsedCoord = liveCandidate.coordinate();
                if (amapPoiId.isBlank()) amapPoiId = safeText(liveCandidate.poiId());
                if (address.isBlank()) address = safeText(liveCandidate.address());
                verifiedSource = "AMAP_POI";
            } else if (catalogAttraction != null && GeoCoordinates.parse(catalogAttraction.getLocation()) != null) {
                rawLocation = catalogAttraction.getLocation();
                parsedCoord = GeoCoordinates.parse(rawLocation);
                verifiedSource = "LOCAL_CATALOG";
            } else if (catalogDining != null && GeoCoordinates.parse(catalogDining.getLocation()) != null) {
                rawLocation = catalogDining.getLocation();
                parsedCoord = GeoCoordinates.parse(rawLocation);
                verifiedSource = "LOCAL_DINING";
            } else if (geoRouteGateway != null && geoRouteGateway.isConfigured()) {
                unverifiedStops.add(name);
            }
        } else if (verifiedSource.isBlank()) {
            verifiedSource = !amapPoiId.isBlank() ? "AMAP_POI" : (catalogAttraction != null ? "LOCAL_CATALOG" : "AGENT_AUTHORED");
        }

        String venueId = firstNonBlank(
                text(node, "venueId", ""),
                safeText(inherited.get("venueId")),
                catalogAttraction != null ? catalogAttraction.getId() : "",
                catalogDining != null ? catalogDining.getId() : "",
                !amapPoiId.isBlank() ? ("amap-" + amapPoiId) : "",
                "agent-" + slugFor(name, stopOrder)
        );
        String stopId = allocateUniqueStopId(requestedId, inherited, dayNum, name, stopOrder, assignedIds);

        Map<String, Object> stop = new LinkedHashMap<>();
        stop.put("id", stopId);
        stop.put("stopId", stopId);
        stop.put("venueId", venueId);
        if (!amapPoiId.isBlank()) stop.put("amapPoiId", amapPoiId);
        stop.put("name", name);
        stop.put("displayName", name);
        stop.put("type", type);
        stop.put("icon", isDining ? "餐" : (isFreeTime ? "休" : "景"));
        stop.put("tone", isDining ? "gold" : (isFreeTime ? "teal" : "default"));
        stop.put("category", firstNonBlank(
                text(node, "category", ""),
                catalogAttraction != null ? safeText(catalogAttraction.getCategory()) : "",
                catalogDining != null ? safeText(catalogDining.getCategory()) : "",
                isDining ? "特色美食" : (isFreeTime ? "自由活动" : "文旅地标")
        ));
        stop.put("district", district);
        if (!address.isBlank()) stop.put("address", address);
        if (parsedCoord != null && parsedCoord.isValid()) stop.put("location", rawLocation);
        stop.put("time", firstNonBlank(text(node, "time", ""), text(node, "period", ""), isDining ? "用餐时段" : "待安排"));
        stop.put("duration", firstNonBlank(
                text(node, "duration", ""),
                catalogAttraction != null ? safeText(catalogAttraction.getDuration()) : "",
                isDining ? "约 60 分钟" : "约 90 分钟"
        ));
        stop.put("summary", firstNonBlank(
                text(node, "summary", ""),
                catalogAttraction != null ? safeText(catalogAttraction.getSummary()) : "",
                isDining ? "精选当地口碑餐饮门店" : "智能体编排亮点景点"
        ));
        if (!text(node, "recommendationReason", "").isBlank()) {
            stop.put("recommendationReason", text(node, "recommendationReason", ""));
        } else if (!safeText(inherited.get("recommendationReason")).isBlank()) {
            stop.put("recommendationReason", safeText(inherited.get("recommendationReason")));
        }
        String ticket = firstNonBlank(
                text(node, "ticket", ""),
                catalogAttraction != null ? safeText(catalogAttraction.getTicket()) : "",
                catalogDining != null ? safeText(catalogDining.getAverageCost()) : ""
        );
        if (!ticket.isBlank()) stop.put("ticket", ticket);
        String openingHours = firstNonBlank(
                text(node, "openingHours", ""),
                catalogAttraction != null ? safeText(catalogAttraction.getOpeningHours()) : "",
                safeText(inherited.get("openingHours"))
        );
        if (!openingHours.isBlank()) stop.put("openingHours", openingHours);
        if (!text(node, "bookingPolicy", "").isBlank()) {
            stop.put("bookingPolicy", text(node, "bookingPolicy", ""));
        }
        String specialtyDish = firstNonBlank(
                text(node, "specialtyDish", ""),
                catalogDining != null ? safeText(catalogDining.getSpecialtyDish()) : ""
        );
        if (!specialtyDish.isBlank()) stop.put("specialtyDish", specialtyDish);
        if (!verifiedSource.isBlank()) stop.put("verifiedSource", verifiedSource);
        if (!text(node, "routePreference", "").isBlank()) {
            stop.put("routePreference", text(node, "routePreference", ""));
        }
        if (node.has("reasonCitations") && node.get("reasonCitations").isArray()) {
            stop.put("reasonCitations", objectMapper.convertValue(node.get("reasonCitations"), List.class));
        }
        return stop;
    }

    private GeoPoiCandidate resolveLivePoi(String query, String city, boolean dining) {
        if (geoRouteGateway == null || !geoRouteGateway.isConfigured() || query.isBlank()) {
            return null;
        }
        String types = dining ? "050000" : "";
        GeoPoiOutcome outcome = types.isEmpty()
                ? geoRouteGateway.searchPoi(query, city)
                : geoRouteGateway.searchPoi(query, city, types);
        if (outcome == null || outcome.status() != GeoOutcomeStatus.SUCCESS || outcome.candidates() == null || outcome.candidates().isEmpty()) {
            return null;
        }
        return outcome.candidates().stream()
                .filter(c -> c != null && c.coordinate() != null && c.coordinate().isValid())
                .findFirst()
                .orElse(null);
    }

    private Attraction matchCatalogAttraction(JsonNode node, String city, String name, Map<String, Object> inherited) {
        if (attractionService == null) return null;
        String venueId = firstNonBlank(text(node, "venueId", ""), safeText(inherited.get("venueId")));
        if (!venueId.isBlank() && !venueId.startsWith("amap-") && !venueId.startsWith("agent-")) {
            Attraction direct = attractionService.get(venueId);
            if (direct != null) return direct;
        }
        List<Attraction> list = attractionService.list(city, null, null);
        if (list == null) return null;
        for (Attraction a : list) {
            if (a != null && a.getName() != null && (a.getName().equalsIgnoreCase(name) || a.getName().contains(name) || name.contains(a.getName()))) {
                return a;
            }
        }
        return null;
    }

    private DiningVenue matchCatalogDining(JsonNode node, String name) {
        if (diningVenueService == null) return null;
        List<DiningVenue> list = diningVenueService.listAll();
        if (list == null) return null;
        String venueId = text(node, "venueId", "");
        for (DiningVenue v : list) {
            if (v == null) continue;
            if (!venueId.isBlank() && venueId.equals(v.getId())) return v;
            if (v.getName() != null && (v.getName().equalsIgnoreCase(name) || v.getName().contains(name))) return v;
        }
        return null;
    }

    private Map<String, Map<String, Object>> indexExistingStops(Map<String, Object> trip) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        Object rawDays = trip.get("days");
        if (!(rawDays instanceof List<?> days)) return map;
        for (Object dayObj : days) {
            if (!(dayObj instanceof Map<?, ?> d)) continue;
            int dayNum = d.get("day") instanceof Number n ? n.intValue() : 1;
            if (!(d.get("stops") instanceof List<?> stops)) continue;
            for (Object sObj : stops) {
                if (!(sObj instanceof Map<?, ?> s)) continue;
                Map<String, Object> copy = copyMap(s);
                String id = firstNonBlank(safeText(copy.get("id")), safeText(copy.get("stopId")));
                String name = safeText(copy.get("name"));
                if (!id.isBlank()) map.put("id:" + id, copy);
                if (!name.isBlank()) {
                    map.putIfAbsent("dayname:" + dayNum + ":" + name.trim(), copy);
                    map.putIfAbsent("name:" + name.trim(), copy);
                }
            }
        }
        return map;
    }

    private Map<String, Object> findExistingStop(Map<String, Map<String, Object>> index, String requestedId, int dayNum, String name) {
        if (index == null || index.isEmpty()) return Map.of();
        if (!requestedId.isBlank() && index.containsKey("id:" + requestedId)) {
            return index.get("id:" + requestedId);
        }
        if (index.containsKey("dayname:" + dayNum + ":" + name.trim())) {
            return index.get("dayname:" + dayNum + ":" + name.trim());
        }
        return index.getOrDefault("name:" + name.trim(), Map.of());
    }

    /**
     * 对已有行程执行局部补丁操作（单站替换、删站、加站、移天、更新时段），保持未改动天/站原样不变。
     */
    private SurgicalPatchOutcome applySurgicalOperations(
            Map<String, Object> beforeTrip,
            JsonNode operations,
            String city,
            List<String> unverifiedStops) {
        List<Map<String, Object>> days = copyDayList(beforeTrip.get("days"));
        if (days.isEmpty()) {
            return SurgicalPatchOutcome.fail("NO_EXISTING_DAYS", "当前行程缺少每日日程，无法执行局部修改。");
        }
        Set<String> assignedIds = collectAssignedStopIds(days);

        for (JsonNode opNode : operations) {
            if (opNode == null || !opNode.isObject()) {
                return SurgicalPatchOutcome.fail("INVALID_OPERATION", "operations 元素必须为 JSON 对象。");
            }
            String op = text(opNode, "op", "").toUpperCase(Locale.ROOT);
            int targetDayHint = opNode.hasNonNull("day") ? opNode.get("day").asInt(0)
                    : (opNode.hasNonNull("fromDay") ? opNode.get("fromDay").asInt(0) : 0);
            String targetStopId = firstNonBlank(text(opNode, "targetStopId", ""), text(opNode, "stopId", ""));
            String targetStopName = firstNonBlank(text(opNode, "targetStopName", ""), text(opNode, "targetName", ""));

            switch (op) {
                case "REPLACE_STOP" -> {
                    StopLocation loc = locateStop(days, targetDayHint, targetStopId, targetStopName);
                    if (loc == null) {
                        return SurgicalPatchOutcome.fail("TARGET_STOP_NOT_FOUND",
                                "未在当前行程中找到待替换站点（targetStopId=" + targetStopId + ", targetStopName=" + targetStopName + "），请检查 get_current_trip_draft。");
                    }
                    JsonNode newStopNode = opNode.get("newStop");
                    if (newStopNode == null || !newStopNode.isObject() || text(newStopNode, "name", "").isBlank()) {
                        return SurgicalPatchOutcome.fail("MISSING_NEW_STOP", "REPLACE_STOP 必须提供包含真实 name 的 newStop 对象。");
                    }
                    Map<String, Object> oldStop = loc.dayStops().get(loc.index());
                    Map<String, Object> rebuilt = normalizeStop(newStopNode, loc.dayNumber(), loc.index() + 1, city, Map.of(), assignedIds, unverifiedStops);
                    if (text(newStopNode, "time", "").isBlank() && !safeText(oldStop.get("time")).isBlank()) {
                        rebuilt.put("time", safeText(oldStop.get("time")));
                    }
                    loc.dayStops().set(loc.index(), rebuilt);
                }
                case "REMOVE_STOP" -> {
                    StopLocation loc = locateStop(days, targetDayHint, targetStopId, targetStopName);
                    if (loc == null) {
                        return SurgicalPatchOutcome.fail("TARGET_STOP_NOT_FOUND",
                                "未在当前行程中找到待删除站点：" + firstNonBlank(targetStopName, targetStopId));
                    }
                    if (loc.dayStops().size() <= 1) {
                        return SurgicalPatchOutcome.fail("CANNOT_EMPTY_DAY", "第 " + loc.dayNumber() + " 天至少需保留 1 个站点，请改为替换站点或调整天数。");
                    }
                    loc.dayStops().remove(loc.index());
                }
                case "ADD_STOP" -> {
                    int addDay = targetDayHint > 0 ? targetDayHint : 1;
                    Map<String, Object> dayMap = findDayByNumber(days, addDay);
                    if (dayMap == null) {
                        return SurgicalPatchOutcome.fail("TARGET_DAY_NOT_FOUND", "当前行程不存在第 " + addDay + " 天。");
                    }
                    JsonNode newStopNode = opNode.get("newStop");
                    if (newStopNode == null || !newStopNode.isObject() || text(newStopNode, "name", "").isBlank()) {
                        return SurgicalPatchOutcome.fail("MISSING_NEW_STOP", "ADD_STOP 必须提供包含真实 name 的 newStop 对象。");
                    }
                    List<Map<String, Object>> stops = mutableStops(dayMap);
                    int insertPos = resolveInsertIndex(stops, opNode);
                    Map<String, Object> added = normalizeStop(newStopNode, addDay, insertPos + 1, city, Map.of(), assignedIds, unverifiedStops);
                    stops.add(insertPos, added);
                }
                case "MOVE_STOP" -> {
                    StopLocation loc = locateStop(days, targetDayHint, targetStopId, targetStopName);
                    if (loc == null) {
                        return SurgicalPatchOutcome.fail("TARGET_STOP_NOT_FOUND",
                                "未找到待移动站点：" + firstNonBlank(targetStopName, targetStopId));
                    }
                    int toDay = opNode.hasNonNull("toDay") ? opNode.get("toDay").asInt(loc.dayNumber()) : loc.dayNumber();
                    Map<String, Object> destDayMap = findDayByNumber(days, toDay);
                    if (destDayMap == null) {
                        return SurgicalPatchOutcome.fail("TARGET_DAY_NOT_FOUND", "目标天第 " + toDay + " 天不存在。");
                    }
                    Map<String, Object> moved = loc.dayStops().remove(loc.index());
                    if (loc.dayNumber() != toDay && loc.dayStops().isEmpty()) {
                        return SurgicalPatchOutcome.fail("CANNOT_EMPTY_DAY", "第 " + loc.dayNumber() + " 天移出后将为空，请保留至少 1 个站点。");
                    }
                    List<Map<String, Object>> destStops = mutableStops(destDayMap);
                    int insertPos = resolveInsertIndex(destStops, opNode);
                    destStops.add(insertPos, moved);
                }
                case "UPDATE_STOP" -> {
                    StopLocation loc = locateStop(days, targetDayHint, targetStopId, targetStopName);
                    if (loc == null) {
                        return SurgicalPatchOutcome.fail("TARGET_STOP_NOT_FOUND",
                                "未找到待更新站点：" + firstNonBlank(targetStopName, targetStopId));
                    }
                    Map<String, Object> stop = loc.dayStops().get(loc.index());
                    for (String field : List.of("time", "duration", "summary", "recommendationReason", "openingHours", "bookingPolicy")) {
                        String val = text(opNode, field, "");
                        if (!val.isBlank()) {
                            stop.put(field, val);
                        }
                    }
                }
                default -> {
                    return SurgicalPatchOutcome.fail("UNSUPPORTED_OPERATION", "不支持的局部补丁操作：" + op);
                }
            }
        }
        return SurgicalPatchOutcome.ok(days);
    }

    private List<Map<String, Object>> copyDayList(Object rawDays) {
        List<Map<String, Object>> copy = new ArrayList<>();
        if (!(rawDays instanceof List<?> list)) return copy;
        for (Object dObj : list) {
            if (!(dObj instanceof Map<?, ?> rawDay)) continue;
            Map<String, Object> dayCopy = copyMap(rawDay);
            List<Map<String, Object>> stopsCopy = new ArrayList<>();
            if (rawDay.get("stops") instanceof List<?> rawStops) {
                for (Object sObj : rawStops) {
                    if (sObj instanceof Map<?, ?> s) {
                        stopsCopy.add(copyMap(s));
                    }
                }
            }
            dayCopy.put("stops", stopsCopy);
            copy.add(dayCopy);
        }
        return copy;
    }

    private Set<String> collectAssignedStopIds(List<Map<String, Object>> days) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> d : days) {
            if (d.get("stops") instanceof List<?> stops) {
                for (Object sObj : stops) {
                    if (sObj instanceof Map<?, ?> s) {
                        String id = firstNonBlank(safeText(s.get("id")), safeText(s.get("stopId")));
                        if (!id.isBlank()) ids.add(id);
                    }
                }
            }
        }
        return ids;
    }

    private Map<String, Object> findDayByNumber(List<Map<String, Object>> days, int dayNumber) {
        for (Map<String, Object> d : days) {
            int num = d.get("day") instanceof Number n ? n.intValue() : 0;
            if (num == dayNumber) return d;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mutableStops(Map<String, Object> dayMap) {
        Object raw = dayMap.get("stops");
        if (raw instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        List<Map<String, Object>> created = new ArrayList<>();
        dayMap.put("stops", created);
        return created;
    }

    private int resolveInsertIndex(List<Map<String, Object>> stops, JsonNode opNode) {
        if (opNode.hasNonNull("insertIndex")) {
            return Math.max(0, Math.min(stops.size(), opNode.get("insertIndex").asInt(stops.size())));
        }
        String afterId = text(opNode, "insertAfterStopId", "");
        String afterName = text(opNode, "insertAfterStopName", "");
        if (!afterId.isBlank() || !afterName.isBlank()) {
            for (int i = 0; i < stops.size(); i++) {
                Map<String, Object> s = stops.get(i);
                String id = firstNonBlank(safeText(s.get("id")), safeText(s.get("stopId")));
                String name = safeText(s.get("name"));
                if ((!afterId.isBlank() && afterId.equals(id))
                        || (!afterName.isBlank() && (name.equalsIgnoreCase(afterName) || name.contains(afterName)))) {
                    return i + 1;
                }
            }
        }
        return stops.size();
    }

    private StopLocation locateStop(List<Map<String, Object>> days, int dayHint, String targetStopId, String targetStopName) {
        // 1. 优先精确 stopId 匹配
        if (!targetStopId.isBlank()) {
            for (Map<String, Object> d : days) {
                int dayNum = d.get("day") instanceof Number n ? n.intValue() : 1;
                if (dayHint > 0 && dayNum != dayHint) continue;
                List<Map<String, Object>> stops = mutableStops(d);
                for (int i = 0; i < stops.size(); i++) {
                    String id = firstNonBlank(safeText(stops.get(i).get("id")), safeText(stops.get(i).get("stopId")));
                    if (targetStopId.equals(id)) {
                        return new StopLocation(dayNum, stops, i);
                    }
                }
            }
        }
        // 2. 名称精确匹配，再模糊子串匹配
        String query = targetStopName == null ? "" : targetStopName.trim();
        if (query.isBlank()) return null;
        for (boolean exactOnly : List.of(true, false)) {
            for (Map<String, Object> d : days) {
                int dayNum = d.get("day") instanceof Number n ? n.intValue() : 1;
                if (dayHint > 0 && dayNum != dayHint) continue;
                List<Map<String, Object>> stops = mutableStops(d);
                for (int i = 0; i < stops.size(); i++) {
                    String name = safeText(stops.get(i).get("name")).trim();
                    boolean matched = exactOnly ? name.equalsIgnoreCase(query) : (name.contains(query) || query.contains(name));
                    if (matched) {
                        return new StopLocation(dayNum, stops, i);
                    }
                }
            }
        }
        return null;
    }

    private record StopLocation(int dayNumber, List<Map<String, Object>> dayStops, int index) {}

    private record SurgicalPatchOutcome(boolean ok, String errorCode, String message, List<Map<String, Object>> days) {
        static SurgicalPatchOutcome ok(List<Map<String, Object>> days) {
            return new SurgicalPatchOutcome(true, "", "", days);
        }
        static SurgicalPatchOutcome fail(String code, String msg) {
            return new SurgicalPatchOutcome(false, code, msg, List.of());
        }
    }

    private String allocateUniqueStopId(String requestedId, Map<String, Object> inherited, int dayNum,
                                        String name, int stopOrder, Set<String> assignedIds) {
        String candidate = firstNonBlank(requestedId, safeText(inherited.get("id")), safeText(inherited.get("stopId")));
        if (candidate.isBlank()) {
            candidate = "day" + dayNum + "-" + stopOrder + "-" + slugFor(name, stopOrder);
        }
        String unique = candidate;
        int suffix = 2;
        while (assignedIds.contains(unique)) {
            unique = candidate + "-" + suffix++;
        }
        assignedIds.add(unique);
        return unique;
    }

    private String resolveStopType(String explicitType, JsonNode node, Map<String, Object> inherited) {
        if (Set.of("SCENIC", "DINING", "FREE_TIME").contains(explicitType)) return explicitType;
        String inheritedType = safeText(inherited.get("type")).toUpperCase(Locale.ROOT);
        if (Set.of("SCENIC", "DINING", "FREE_TIME").contains(inheritedType)) return inheritedType;
        if (!text(node, "specialtyDish", "").isBlank()) return "DINING";
        return "SCENIC";
    }

    private Map<String, Object> buildConstraintsMap(JsonNode args, String city, int dayCount) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("destination", city);
        map.put("durationDays", dayCount);
        if (args.has("constraints") && args.get("constraints").isObject()) {
            args.get("constraints").fields().forEachRemaining(e -> {
                if (!e.getValue().isNull()) {
                    map.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class));
                }
            });
        }
        map.putIfAbsent("companions", firstNonBlank(text(args, "companions", ""), "自由组合"));
        map.putIfAbsent("walkingTolerance", firstNonBlank(text(args, "walkingTolerance", ""), "适中"));
        return map;
    }

    private AppliedPreferencesSnapshot resolvePreferencesSnapshot(String city, Map<String, Object> constraintsMap) {
        if (preferencesResolver == null) return AppliedPreferencesSnapshot.empty();
        try {
            TravelConstraints tc = TravelConstraints.builder()
                    .destination(city)
                    .durationDays(constraintsMap.get("durationDays") instanceof Number n ? n.intValue() : 2)
                    .companions(safeText(constraintsMap.get("companions")))
                    .walkingTolerance(safeText(constraintsMap.get("walkingTolerance")))
                    .build();
            PlannerPreferencesResolver.Resolution res = preferencesResolver.resolve(tc, true);
            return res != null && res.snapshot() != null ? res.snapshot() : AppliedPreferencesSnapshot.empty();
        } catch (RuntimeException ex) {
            log.debug("[AgentAuthoring] 读取个人偏好快照降级为空: {}", ex.getMessage());
            return AppliedPreferencesSnapshot.empty();
        }
    }

    private String validateLimits(Map<String, Object> trip) {
        try {
            planLimits.validate(trip);
            return null;
        } catch (IllegalArgumentException invalid) {
            return invalid.getMessage() == null ? "行程结构超过系统容量限制" : invalid.getMessage();
        }
    }

    private void hydrateSafely(Map<String, Object> trip) {
        if (routeGateway == null) return;
        try (var ignored = OperationDeadline.open(HYDRATION_TIMEOUT_MS)) {
            HydrationResult hr = routeGateway.hydrate(trip);
            if (hr != null && hr.mode() != null && !hr.mode().isBlank()) {
                trip.put("sourceMode", hr.mode());
            }
        } catch (Exception ex) {
            log.warn("[AgentAuthoring] 高德路线/天气水化部分失败，保留坐标与基础信息: {}", ex.getMessage());
        }
    }

    private void refreshTripEnvelopeMetrics(Map<String, Object> trip, int version, String actionLabel,
                                            String changeSummary, List<String> changedSegments) {
        trip.put("version", version);
        Map<String, Object> sourceStatus = copyMap(trip.get("sourceStatus"));
        sourceStatus.putIfAbsent("planning", "Agent 原生自由编排");
        sourceStatus.putIfAbsent("weather", "高德天气已核验");
        trip.put("sourceStatus", sourceStatus);
        if (!trip.containsKey("routeDataStatus")) {
            Object mode = trip.get("sourceMode");
            trip.put("routeDataStatus", "高德实时接口".equals(mode) ? "VERIFIED_AMAP" : "ESTIMATED");
        }
        Map<String, Object> metrics = copyMap(trip.get("qualityMetrics"));
        metrics.put("lastActionLabel", actionLabel);
        metrics.put("lastChangeSummary", changeSummary);
        metrics.put("changedSegments", changedSegments == null ? List.of() : changedSegments);
        trip.put("qualityMetrics", metrics);
    }

    private List<Map<String, Object>> computeStopChanges(Map<String, Object> beforeTrip, Map<String, Object> afterTrip) {
        Map<String, String> beforeDayStop = dayStopNamesMap(beforeTrip);
        Map<String, String> afterDayStop = dayStopNamesMap(afterTrip);
        List<Map<String, Object>> changes = new ArrayList<>();

        for (var entry : afterDayStop.entrySet()) {
            if (!beforeDayStop.containsKey(entry.getKey())) {
                changes.add(Map.of("action", "ADDED", "key", entry.getKey(), "stopName", entry.getValue()));
            }
        }
        for (var entry : beforeDayStop.entrySet()) {
            if (!afterDayStop.containsKey(entry.getKey())) {
                changes.add(Map.of("action", "REMOVED", "key", entry.getKey(), "stopName", entry.getValue()));
            }
        }
        return changes;
    }

    private List<String> collectChangedSegments(List<Map<String, Object>> stopChanges) {
        if (stopChanges == null || stopChanges.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (Map<String, Object> change : stopChanges) {
            String action = "ADDED".equals(change.get("action")) ? "新增 " : "移除 ";
            out.add(action + safeText(change.get("stopName")));
        }
        return out;
    }

    private Map<String, String> dayStopNamesMap(Map<String, Object> trip) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(trip.get("days") instanceof List<?> days)) return out;
        for (Object dayObj : days) {
            if (!(dayObj instanceof Map<?, ?> d)) continue;
            String dayKey = String.valueOf(d.get("day"));
            if (!(d.get("stops") instanceof List<?> stops)) continue;
            int idx = 1;
            for (Object sObj : stops) {
                if (!(sObj instanceof Map<?, ?> s)) continue;
                String name = safeText(s.get("name"));
                out.put("D" + dayKey + "#" + idx++ + ":" + name, "第" + dayKey + "天 · " + name);
            }
        }
        return out;
    }

    private List<String> resolvePinnedStopNames(Map<String, Object> trip) {
        Map<String, Map<String, Object>> all = PlannerPins.stops(trip);
        List<String> names = new ArrayList<>();
        for (String id : PlannerPins.read(trip)) {
            Map<String, Object> s = all.get(id);
            names.add(s != null && s.get("name") != null ? String.valueOf(s.get("name")) : id);
        }
        return names;
    }

    private void purgeExpiredProposals() {
        long now = System.currentTimeMillis();
        if (pendingProposals.size() >= MAX_PENDING_PROPOSALS) {
            pendingProposals.entrySet().removeIf(e -> now > e.getValue().expiresAtMillis());
        }
        if (pendingProposals.size() >= MAX_PENDING_PROPOSALS) {
            pendingProposals.clear();
        }
    }

    private String buildDefaultSubtitle(Map<String, Object> c) {
        List<String> parts = new ArrayList<>();
        String comp = safeText(c.get("companions"));
        String walk = safeText(c.get("walkingTolerance"));
        if (!comp.isBlank() && !"未提供".equals(comp)) parts.add(comp);
        if (!walk.isBlank() && !"未提供".equals(walk)) parts.add("步行" + walk);
        return parts.isEmpty() ? "全智能体自由编排" : String.join(" · ", parts);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> copyMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), deepCopyValue(v)));
            return copy;
        }
        return new LinkedHashMap<>();
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> m) return copyMap(m);
        if (value instanceof List<?> l) {
            List<Object> list = new ArrayList<>(l.size());
            l.forEach(item -> list.add(deepCopyValue(item)));
            return list;
        }
        return value;
    }

    private static String slugFor(String name, int fallbackOrder) {
        String ascii = name == null ? "" : name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "");
        if (ascii.length() > 16) ascii = ascii.substring(0, 16);
        return ascii.isBlank() ? "stop" + fallbackOrder : ascii;
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field)) return fallback;
        String val = node.get(field).asText("").trim();
        return val.isEmpty() ? fallback : val;
    }

    private static String safeText(Object val) {
        return val == null ? "" : String.valueOf(val).trim();
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) return c.trim();
        }
        return "";
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}
