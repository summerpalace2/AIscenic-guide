package com.ai.guide.domain.planner.service.adjustment;

import com.ai.guide.domain.attraction.model.Attraction;
import com.ai.guide.domain.planner.engine.RouteAwarePlanner;
import com.ai.guide.domain.planner.model.ConversationIntentType;
import com.ai.guide.domain.planner.model.PlanAdjustmentIntent;
import com.ai.guide.domain.planner.model.TravelConstraints;
import com.ai.guide.domain.planner.service.PlannerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ai.guide.domain.planner.service.adjustment.PlanAdjustmentHelper.*;

/**
 * 站点移除调整策略（处理 REMOVE_STOP 意图）
 */
@Component
public class RemoveStopAdjustmentStrategy implements AdjustmentStrategy {

    private final RouteAwarePlanner routeAwarePlanner;

    @Autowired
    public RemoveStopAdjustmentStrategy(RouteAwarePlanner routeAwarePlanner) {
        this.routeAwarePlanner = routeAwarePlanner;
    }

    @Override
    public boolean supports(ConversationIntentType intentType) {
        return intentType == ConversationIntentType.REMOVE_STOP;
    }

    @Override
    public void apply(AdjustmentContext ctx) {
        PlanAdjustmentIntent intent = ctx.getIntent();
        Map<String, Object> draftTrip = ctx.getDraftTrip();
        Map<String, Attraction> byId = ctx.getAttractionsById();
        TravelConstraints constraints = ctx.getConstraints();

        String targetId = intent.getTargetStopId();
        StopLocation target = locateStop(draftTrip, targetId);
        if (target == null && targetId != null && !targetId.isBlank()) {
            target = locateStopByVenue(draftTrip, targetId);
        }
        if (target == null) {
            ctx.setFatalError(error(400, "目标景点不存在。", ctx));
            return;
        }

        // 最小站点数不变量守卫（DEF-R3-05）：当天至少保留 1 个站点，防止空天数引发排程雪崩
        if (target.stops().size() <= 1) {
            ctx.setFatalError(error(400, "当天行程至少需要保留 1 个站点，不能直接删空。如需调整天数，请使用缩减天数或重新规划功能。", ctx));
            return;
        }

        String stopId = String.valueOf(target.stop().get("id"));
        target.stops().remove(target.index());
        if (target.index() == 0 && !target.stops().isEmpty())
            RouteAwarePlanner.invalidateIncomingRoute(target.stops().get(0));
        ctx.getChangedSegments().add(stopId);
        LocalAdjustmentRepair.repair(draftTrip, number(target.day().get("day")), byId, constraints, intent, null, routeAwarePlanner);
        ctx.getOptionPlans().put("option-1", draftTrip);
    }

    private PlannerService.ServiceResult error(int status, String message, AdjustmentContext ctx) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        if (ctx != null && ctx.getSession() != null) {
            body.put("sessionId", ctx.getSession().sessionId());
            body.put("trip", ctx.getSession().trip());
            body.put("appliedPreferences", ctx.getSession().appliedPreferences().asMap());
        }
        return new PlannerService.ServiceResult(status, body);
    }
}
