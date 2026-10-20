package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.language.IntentLanguagePolicy;

import com.ai.guide.domain.planner.model.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Server-owned expiring continuation: a short answer fills only the requested slot. */
public final class PendingIntentContinuations {
    public record Pending(String intentId, int baseRevision, long expiresAt, PlanAdjustmentIntent intent) {}
    public static final class State {
        public Pending pending;
        public PlanAdjustmentIntent remaining;
        public String generation;
        public String epoch;
        public boolean pendingTimeScopeResolved;
        public boolean remainingTimeScopeResolved;
    }
    private final com.ai.guide.domain.planner.gateway.PlannerConversationStatePort store;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private record ActiveState(String session,State state) {}
    private final ThreadLocal<ActiveState> active = new ThreadLocal<>();
    private final LongSupplier clock;
    private final ConversationIntentClassifier classifier;
    public PendingIntentContinuations() { this(new ConversationIntentClassifier(),System::currentTimeMillis); }
    public PendingIntentContinuations(LongSupplier clock) { this(new ConversationIntentClassifier(),clock); }
    public PendingIntentContinuations(ConversationIntentClassifier classifier) { this(classifier,System::currentTimeMillis); }
    public PendingIntentContinuations(ConversationIntentClassifier classifier,LongSupplier clock) {
        this(classifier,clock,new InMemoryPlannerConversationState());
    }
    public PendingIntentContinuations(ConversationIntentClassifier classifier,LongSupplier clock,
            com.ai.guide.domain.planner.gateway.PlannerConversationStatePort store) {
        this.classifier = classifier == null ? new ConversationIntentClassifier() : classifier;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.store = Objects.requireNonNull(store);
    }
    private <T> T operate(String session,java.util.function.Function<State,T> action) {
        ActiveState outer = active.get();
        if (outer!=null && Objects.equals(outer.session(),session)) return action.apply(outer.state());
        return store.withSessionLock(session,() -> {
            State state = mapper.convertValue(store.continuationState(session),State.class);
            if(state.pending!=null) state.pending.intent().setTimeScopeResolved(state.pendingTimeScopeResolved);
            if(state.remaining!=null) state.remaining.setTimeScopeResolved(state.remainingTimeScopeResolved);
            active.set(new ActiveState(session,state));
            try {
                T result = action.apply(state);
                state.pendingTimeScopeResolved=state.pending!=null && state.pending.intent().isTimeScopeResolved();
                state.remainingTimeScopeResolved=state.remaining!=null && state.remaining.isTimeScopeResolved();
                store.saveContinuationState(session,mapper.convertValue(state,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}));
                return result;
            } finally { if (outer==null) active.remove(); else active.set(outer); }
        });
    }
    private String begin(State state) {
        if (state.epoch == null) state.epoch = UUID.randomUUID().toString();
        state.generation = UUID.randomUUID().toString(); return state.generation;
    }
    private void reset(State state) { begin(state); state.pending=null; state.remaining=null; state.epoch=UUID.randomUUID().toString(); }
    public String begin(String session) { return operate(session,this::begin); }
    public boolean current(String session,String ticket) { return operate(session,state -> Objects.equals(state.generation,ticket)); }
    public boolean commit(String session,String ticket,Runnable action) { return operate(session,state -> {
        if (!Objects.equals(state.generation,ticket)) return false; action.run(); return true;
    }); }
    public void reset(String session) { operate(session,state -> { reset(state);return null; }); }
    public void afterApply(String session,int revision) { operate(session,state -> {
        var remaining=state.remaining; reset(state);
        if (remaining!=null && !remaining.getCompoundActions().isEmpty()) state.pending=remember(revision,remaining);
        return null;
    }); }
    public String epoch(String session) { return operate(session,state -> state.epoch==null?"":state.epoch); }
    public boolean validProposal(String session,String proposalEpoch,long createdAt) { return operate(session,state ->
        state.epoch!=null && proposalEpoch!=null && !proposalEpoch.isBlank() && !"INITIAL".equals(proposalEpoch) && state.epoch.equals(proposalEpoch)); }
    public boolean validProposal(String session,long createdAt) { return false; }
    public void clear(String session) { operate(session,state -> { state.pending=null;return null; }); }
    public Pending pending(String session) { return operate(session,state -> state.pending); }
    public boolean deferAfterApply(String session,String ticket,PlanAdjustmentIntent original,List<String> actions) {
        return operate(session,state -> {
            if (!Objects.equals(state.generation,ticket)) return false;
            var queued=new ArrayList<String>(state.remaining==null?List.of():state.remaining.getCompoundActions());
            for (String action:actions) if (!queued.contains(action)) queued.add(action);
            state.remaining=original.toBuilder().type(ConversationIntentType.CLARIFICATION).operation("CLARIFICATION")
                    .compoundActions(queued).requiresClarification(true).missingFields(List.of("COMPOUND_ACTION_ORDER"))
                    .clarificationQuestion("剩余要求尚未执行：\n"+String.join("\n",queued)+"\n回复执行下一个，逐项查看预览。").build();
            return true;
        });
    }
    public void abandonCompoundQueue(String session) { operate(session,state -> { state.remaining=null;return null; }); }
    private Pending remember(int revision,PlanAdjustmentIntent intent) { inferMissing(intent); return new Pending(UUID.randomUUID().toString(),revision,clock.getAsLong()+600_000,intent); }
    public Pending remember(String session,int revision,PlanAdjustmentIntent intent) { return operate(session,state -> state.pending=remember(revision,intent)); }
    public PlanAdjustmentIntent resume(String session,int revision,String id,String message,PlanPageContext context,String ticket) {
        return operate(session,state -> Objects.equals(state.generation,ticket)?resume(state,session,revision,id,message,context):null);
    }
    public PlanAdjustmentIntent resume(String session,int revision,String id,String message,PlanPageContext context) {
        return operate(session,state -> resume(state,session,revision,id,message,context));
    }
    public PlanAdjustmentIntent resume(String session,int revision,String id,String message,PlanPageContext context,String ticket,
            PlannerCalendarDateResolver.Reference calendar) {
        return operate(session,state -> Objects.equals(state.generation,ticket)?resume(state,session,revision,id,message,context,calendar):null);
    }
    private PlanAdjustmentIntent resume(State state,String session,int revision,String id,String message,PlanPageContext context) {
        return resume(state,session,revision,id,message,context,null);
    }
    private PlanAdjustmentIntent resume(State state,String session,int revision,String id,String message,PlanPageContext context,
            PlannerCalendarDateResolver.Reference calendar) {
        Pending pending = state.pending;
        if (pending == null) return null;
        if (pending.baseRevision() != revision || pending.expiresAt() <= clock.getAsLong()) { state.pending = null; return null; }
        if (id != null && !id.isBlank() && !id.equals(pending.intentId())) return null;
        String text = message == null ? "" : message.trim();
        PlanAdjustmentIntent original = pending.intent();
        String field = original.getMissingFields().isEmpty() ? "" : original.getMissingFields().get(0);
        if ("COMPOUND_ACTION_ORDER".equals(field)) {
            if (IntentLanguagePolicy.positiveText(text).matches(".*(?:取消|停止|重新开始).*")) { state.pending = null; state.remaining = null; return null; }
            List<String> actions = original.getCompoundActions().isEmpty()
                    ? IntentLanguagePolicy.distinctActionClauses(original.getRawMessage()) : original.getCompoundActions();
            var refused = new HashSet<Integer>();
            for (String clause : IntentLanguagePolicy.clauses(text)) {
                if (!IntentLanguagePolicy.positiveText(clause).isBlank()) continue;
                boolean operationRefusal = clause.matches("(?s).*(?:添加|加入|删除|移除|替换|换成|重排|重新规划|优化).*");
                String refusedOperation = classifier.classify(clause.replaceFirst("^(?:我)?(?:不要|不用|别|不想|不)", ""), context).getOperation();
                for (int i = 0; i < actions.size(); i++) {
                    var action = classifier.classify(actions.get(i), context);
                    String venue = action.getReplacementPlaceName() != null ? action.getReplacementPlaceName()
                            : action.getRequestedVenueName() != null ? action.getRequestedVenueName() : action.getTargetStopReference();
                    if (venue != null && !venue.isBlank() && clause.contains(venue)
                            && (!operationRefusal || refusedOperation.equals(action.getOperation()))) refused.add(i);
                }
            }
            var acceptedActions = new ArrayList<String>();
            for (int i = 0; i < actions.size(); i++) if (!refused.contains(i)) acceptedActions.add(actions.get(i));
            String positive = IntentLanguagePolicy.positiveText(text);
            // A venue inside a refusal or a complete replacement command is not
            // a selection of the old queued operation.
            if (positive.isBlank()) {
                if (acceptedActions.isEmpty()) { state.pending = null; state.remaining = null; return null; }
                return original.toBuilder().compoundActions(acceptedActions)
                        .clarificationQuestion("已移除被拒绝的操作。剩余要求：\n" + String.join("\n", acceptedActions)
                                + "\n请选择希望执行的项，或发送新的完整调整要求。").build();
            }
            var standalone = classifier.classify(positive, context);
            if (positive.matches("(?s).*(?:添加|加入|删除|移除|替换|换成|重排|重新规划|优化).*")) {
                state.pending = null; state.remaining = null;
                return inheritProtections(original, standalone, context);
            }
            var choice = java.util.regex.Pattern.compile("(?:执行|选择|选|先做|先执行)?\\s*第?\\s*([一二两三四五六七八九十\\d]+)\\s*(?:个|项|条)?(?:操作|调整)?").matcher(positive);
            Integer index = positive.matches("(?:执行|继续|做)?(?:下一个|下一项|剩下的)") ? Integer.valueOf(1)
                    : choice.matches() ? ConversationIntentClassifier.parseChineseNumber(choice.group(1)) : null;
            if (index == null) {
                var matches = new ArrayList<Integer>();
                for (int i = 0; i < actions.size(); i++) {
                    var action = classifier.classify(actions.get(i), context);
                    String venue = action.getReplacementPlaceName() != null ? action.getReplacementPlaceName() : action.getRequestedVenueName();
                    if (venue != null && !venue.isBlank() && positive.equals(venue)) matches.add(i + 1);
                }
                if (matches.size() == 1) index = matches.get(0);
            }
            if (index == null || index < 1 || index > actions.size()) {
                // A new complete command may replace the old task; short ambiguous answers keep it.
                if (!standalone.isRequiresClarification() && standalone.getType() != ConversationIntentType.UNKNOWN) {
                    state.pending = null; state.remaining = null; return inheritProtections(original, standalone, context);
                }
                return original.toBuilder().clarificationQuestion("请选择第 1 至 " + actions.size() + " 项，或单独发送完整调整要求。").build();
            }
            if (refused.contains(index - 1)) return original.toBuilder().compoundActions(acceptedActions)
                    .clarificationQuestion("你同时拒绝了所选操作，请选择其余操作或重新明确要求。").build();
            var answer = classifier.classify(actions.get(index - 1), context);
            // The selected clause is one operation within the original request;
            // changing the UI's active day does not change its shared constraints.
            Integer actionDay = ConversationIntentClassifier.extractExplicitDayNumber(actions.get(index - 1));
            answer.setDayNumber(actionDay == null ? original.getDayNumber() : actionDay);
            answer.setPreserveReferences(union(original.getPreserveReferences(), answer.getPreserveReferences()));
            answer.setPinnedStopIds(union(union(original.getPinnedStopIds(), answer.getPinnedStopIds()), context.getPinnedStopIds()));
            answer.setPreferences(union(original.getPreferences(), answer.getPreferences()));
            answer.setConditions(union(original.getConditions(), answer.getConditions()));
            classifier.resolveTimeScope(answer);
            if (answer.getTargetStartMinute() == null && !answer.getMissingFields().contains("TIME_RANGE")) {
                answer.setTargetStartMinute(original.getTargetStartMinute());
                answer.setTargetEndMinute(original.getTargetEndMinute());
                answer.setTimeScopeResolved(original.getTargetStartMinute() != null || original.isTimeScopeResolved());
            }
            var remaining = new ArrayList<>(acceptedActions); remaining.remove(actions.get(index - 1));
            if (!remaining.isEmpty()) {
                state.remaining = original.toBuilder().compoundActions(remaining)
                        .clarificationQuestion("剩余要求尚未执行：\n" + String.join("\n", remaining) + "\n回复“执行下一个”或选择序号，逐项查看预览。").build();
            } else state.remaining = null;
            state.pending = null;
            return answer;
        }
        if (calendar != null && calendar.present() && calendar.day() == null) {
            var answer = original.toBuilder().rawMessage(original.getRawMessage()+"，"+text).build();
            PlannerCalendarDateResolver.apply(answer,calendar); return answer;
        }
        Integer statedDay = calendar != null && calendar.present() ? calendar.day() : ConversationIntentClassifier.extractExplicitDayNumber(text);
        var explicit = classifier.classify(text,context);
        boolean explicitCommand = IntentLanguagePolicy.hasAdjustmentAction(text)
                && !ConversationIntentClassifier.isDayOnlyReply(text)
                && !(calendar != null && calendar.dateOnly())
                && ConversationIntentClassifier.isModificationOperation(explicit.getOperation());
        if (explicitCommand && Objects.equals(explicit.getOperation(), original.getOperation()) && completeCommand(explicit)) {
            state.pending = null; state.remaining = null;
            return inheritProtections(original, explicit, context);
        }
        if (statedDay != null && !"DAY_NUMBER".equals(field) && !explicitCommand
                && (ConversationIntentClassifier.isDayOnlyReply(text) || calendar != null && calendar.dateOnly())
                && operationType(original.getOperation()) != ConversationIntentType.UNKNOWN) {
            // A different slot may already be pending. Updating the day does not fill that slot
            // or authorize reusing a concrete stop ID from the previous day.
            var answer = original.toBuilder().dayNumber(statedDay)
                    .targetStopId(Objects.equals(statedDay, original.getDayNumber()) ? original.getTargetStopId() : null)
                    .type(ConversationIntentType.CLARIFICATION).requiresClarification(true)
                    .rawMessage(original.getRawMessage() + "，" + text)
                    .pinnedStopIds(union(original.getPinnedStopIds(), context.getPinnedStopIds()))
                    .clarificationQuestion("已改为第 " + statedDay + " 天。" + nextQuestion(field, original.getClarificationQuestion()))
                    .build();
            state.pending = null;
            return answer;
        }
        if (IntentLanguagePolicy.positiveText(text).matches("(?s).*(?:取消|停止|重新开始).*")
                || (!"DAY_NUMBER".equals(field) && statedDay != null && !Objects.equals(statedDay, original.getDayNumber()))
                || (explicitCommand && explicit.getOperation() != null && !Set.of("UNKNOWN","CLARIFICATION").contains(explicit.getOperation())
                    && !Objects.equals(explicit.getOperation(),original.getOperation()))) {
            state.pending = null; return null;
        }
        int day = original.getDayNumber() == null ? (context.getActiveDay() == null ? 1 : context.getActiveDay()) : original.getDayNumber();
        var answerContext = PlanPageContext.builder().activeDay(day).selectedStopId(original.getTargetStopId())
                .pinnedStopIds(original.getPinnedStopIds()).build();
        PlanAdjustmentIntent answer;
        if ("DAY_NUMBER".equals(field)) {
            Integer answeredDay=statedDay == null ? ConversationIntentClassifier.parseChineseNumber(text.replaceAll("第|天|日|选择|选", "")) : statedDay;
            if (answeredDay == null || answeredDay < 1) return original;
            day=answeredDay;
            var remaining=new ArrayList<>(original.getMissingFields());remaining.remove("DAY_NUMBER");
            ConversationIntentType operationType=switch(original.getOperation()) {
                case "ADD_STOP" -> ConversationIntentType.ADD_STOP;case "REMOVE_STOP" -> ConversationIntentType.REMOVE_STOP;
                case "REPLACE_STOP","SUGGEST_REPLACEMENTS" -> ConversationIntentType.SUGGEST_REPLACEMENTS;
                case "REPLAN_DAY" -> ConversationIntentType.REPLAN_DAY;case "REDUCE_DENSITY","REDUCE_DAY_DENSITY" -> ConversationIntentType.REDUCE_DAY_DENSITY;default -> ConversationIntentType.UNKNOWN;
            };
            answer=original.toBuilder().type(remaining.isEmpty()?operationType:ConversationIntentType.CLARIFICATION).dayNumber(day)
                .missingFields(remaining).requiresClarification(!remaining.isEmpty()).clarificationQuestion(remaining.isEmpty()?null:"请继续补充具体地点或调整条件。").build();
        } else if ("REDUCE_COUNT".equals(field)) {
            Integer count = PlannerQuantityPolicy.count(text);
            if (count == null || count < 1) return original;
            var missing = new ArrayList<>(original.getMissingFields()); missing.remove("REDUCE_COUNT");
            answer = original.toBuilder().reduceCount(count).missingFields(missing)
                    .type(missing.isEmpty() ? ConversationIntentType.REDUCE_DAY_DENSITY : ConversationIntentType.CLARIFICATION)
                    .requiresClarification(!missing.isEmpty()).clarificationQuestion(missing.isEmpty() ? null : nextQuestion(missing.get(0),original.getClarificationQuestion())).build();
        } else if ("REDUCTION_REPHRASE".equals(field)) {
            if (!PlannerQuantityPolicy.reductionRequest(text) || PlannerQuantityPolicy.retainRequest(text)) return original;
            answer = classifier.classify(text, answerContext);
            PlannerQuantityPolicy.normalize(answer);
        } else if ("REPLAN_PURPOSE".equals(field)) {
            Integer choice = ConversationIntentClassifier.parseChineseNumber(text.replaceAll("^(?:选|选择|第)|个$|项$", ""));
            String op = choice == null ? "" : switch(choice) { case 1 -> "REPLAN_DAY"; case 2 -> "REPLACE_STOP"; case 3 -> "REDUCE_DENSITY"; case 4 -> "ADD_STOP"; default -> ""; };
            if (op.isEmpty()) return original;
            answer = PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation(op).scope(choice == 2 ? "STOP" : "DAY")
                    .dayNumber(day).requiresClarification(true).rawMessage(original.getRawMessage()).build();
            if (choice == 3) {
                answer.setMissingFields(List.of("REDUCE_COUNT")); answer.setReduceCount(null);
                answer.setClarificationQuestion("你希望减少多少个景点？请明确数量。");
            }
            else {
                answer.setMissingFields(List.of(choice == 1 ? "REPLAN_PREFERENCE" : choice == 2 ? "TARGET_STOP" : "TARGET_PLACE"));
                answer.setClarificationQuestion(choice == 1 ? "你想怎样重新规划第 " + day + " 天？请告诉我希望保留的兴趣或调整条件。"
                        : "请告诉我第 " + day + " 天要" + (choice == 2 ? "替换" : "添加") + "的具体地点。");
            }
        } else if ("TIME_RANGE".equals(field)) {
            // Parse the correction alone: old invalid clocks are evidence, not execution input.
            var corrected = original.toBuilder().rawMessage(text).targetStartMinute(null).targetEndMinute(null)
                    .timeScopeResolved(false).requiresClarification(false).missingFields(new ArrayList<>()).build();
            classifier.resolveTimeScope(corrected);
            if (corrected.isRequiresClarification() || corrected.getTargetStartMinute() == null) return original;
            var missing = new ArrayList<>(original.getMissingFields()); missing.remove("TIME_RANGE");
            answer = original.toBuilder().targetStartMinute(corrected.getTargetStartMinute()).targetEndMinute(corrected.getTargetEndMinute())
                    .timeScopeResolved(true).missingFields(missing).requiresClarification(!missing.isEmpty())
                    .clarificationQuestion(missing.isEmpty() ? null : original.getClarificationQuestion())
                    .type(missing.isEmpty() ? operationType(original.getOperation()) : ConversationIntentType.CLARIFICATION).build();
        } else {
            if ("TARGET_STOP".equals(field)) {
                String targetReference = IntentLanguagePolicy.positiveText(text);
                if (targetReference.isBlank() || targetReference.matches("(?:随便|都可以|不知道|不确定|任意|取消)")) return original;
                var missing = new ArrayList<>(original.getMissingFields());
                missing.remove("TARGET_STOP");
                answer = original.toBuilder().targetStopReference(targetReference)
                        .type(missing.isEmpty() ? operationType(original.getOperation()) : ConversationIntentType.CLARIFICATION)
                        .missingFields(missing).requiresClarification(!missing.isEmpty())
                        .clarificationQuestion(missing.isEmpty() ? null : original.getClarificationQuestion()).build();
            } else {
            String prefix = switch(field) { case "TARGET_PLACE", "VENUE_OR_PREFERENCE" -> "添加"; case "TARGET_STOP" -> "REMOVE_STOP".equals(original.getOperation()) ? "删除" : "替换";
                case "REPLAN_PREFERENCE", "TIME_RANGE" -> "重新规划行程，"; default -> ""; };
            if (prefix.isEmpty()) return null;
            String slotText = IntentLanguagePolicy.positiveText(text);
            if ("TARGET_STOP".equals(field) && Set.of("REPLACE_STOP", "SUGGEST_REPLACEMENTS").contains(original.getOperation())
                    && original.getReplacementPlaceName() != null && !original.getReplacementPlaceName().isBlank()) {
                answer = classifier.classify("把" + slotText + "替换成" + original.getReplacementPlaceName(), answerContext);
            } else {
                answer = classifier.classify(prefix + (Set.of("TARGET_PLACE", "VENUE_OR_PREFERENCE").contains(field)
                        ? slotText.replaceFirst("^(?:我)?(?:想去|要去|希望去|想添加|添加|加入|去)\\s*", "") : slotText), answerContext);
            }
            if ("TARGET_STOP".equals(field)) {
                if (original.getRequestedVenueName() != null) answer.setRequestedVenueName(original.getRequestedVenueName());
                if (original.getReplacementPlaceName() != null) answer.setReplacementPlaceName(original.getReplacementPlaceName());
                if (original.getReplacementPlaceId() != null) answer.setReplacementPlaceId(original.getReplacementPlaceId());
                if (original.getTargetAttractionId() != null) answer.setTargetAttractionId(original.getTargetAttractionId());
                answer.setOperation(original.getOperation());
                if (answer.getTargetStopId() != null || answer.getTargetStopReference() != null) {
                    var missing = new ArrayList<>(original.getMissingFields()); missing.remove("TARGET_STOP");
                    if (original.getScope() != null) answer.setScope(original.getScope());
                    answer.setMissingFields(missing); answer.setRequiresClarification(!missing.isEmpty());
                    answer.setType(missing.isEmpty() ? operationType(original.getOperation()) : ConversationIntentType.CLARIFICATION);
                    answer.setClarificationQuestion(missing.isEmpty() ? null : original.getClarificationQuestion());
                } else {
                    answer.setType(ConversationIntentType.CLARIFICATION);
                    answer.setScope(original.getScope());
                    answer.setMissingFields(new ArrayList<>(original.getMissingFields()));
                    answer.setRequiresClarification(true);
                    answer.setClarificationQuestion(original.getClarificationQuestion());
                }
            }
            if (Set.of("TARGET_PLACE", "VENUE_OR_PREFERENCE", "REPLAN_PREFERENCE").contains(field)) {
                boolean slotUnsatisfied = slotText.isBlank() || answer.isRequiresClarification() || answer.getType() == ConversationIntentType.UNKNOWN;
                if ("REPLAN_PREFERENCE".equals(field) && answer.getPreferences().isEmpty() && answer.getConditions().isEmpty()) {
                    slotUnsatisfied = true;
                }
                if (slotUnsatisfied) {
                    answer.setType(ConversationIntentType.CLARIFICATION);
                    answer.setOperation(original.getOperation());
                    answer.setScope(original.getScope());
                    answer.setTargetStopId(original.getTargetStopId());
                    answer.setTargetStopReference(original.getTargetStopReference());
                    answer.setMissingFields(new ArrayList<>(original.getMissingFields()));
                    answer.setRequiresClarification(true);
                    answer.setClarificationQuestion(original.getClarificationQuestion());
                } else {
                    var missing = new ArrayList<>(original.getMissingFields());
                    missing.remove(field);
                    answer.setOperation(original.getOperation());
                    if (original.getScope() != null) answer.setScope(original.getScope());
                    if (original.getTargetStopId() != null) answer.setTargetStopId(original.getTargetStopId());
                    if (original.getTargetStopReference() != null) answer.setTargetStopReference(original.getTargetStopReference());
                    if ("REPLACE_STOP".equals(original.getOperation()) || "SUGGEST_REPLACEMENTS".equals(original.getOperation())) {
                        String rep = answer.getReplacementPlaceName();
                        if (rep == null || rep.isBlank()) rep = answer.getRequestedVenueName();
                        if (rep == null || rep.isBlank()) rep = slotText;
                        if (rep != null && !rep.isBlank()) answer.setReplacementPlaceName(rep);
                        if (answer.getReplacementPlaceId() == null && answer.getTargetAttractionId() != null) {
                            answer.setReplacementPlaceId(answer.getTargetAttractionId());
                        }
                    }
                    answer.setMissingFields(missing);
                    answer.setRequiresClarification(!missing.isEmpty());
                    answer.setType(missing.isEmpty() ? operationType(original.getOperation()) : ConversationIntentType.CLARIFICATION);
                    answer.setClarificationQuestion(missing.isEmpty() ? null : nextQuestion(missing.get(0), original.getClarificationQuestion()));
                }
            }
            }
        }
        if (answer.getMissingFields() != null && answer.getMissingFields().contains("DAY_NUMBER")) {
            answer.setDayNumber(original.getDayNumber());
        } else {
            answer.setDayNumber(day);
        }
        answer.setRawMessage(Set.of("REDUCTION_REPHRASE", "REDUCE_COUNT").contains(field) ? text : original.getRawMessage() + "，" + text);
        answer.setPreserveReferences(union(original.getPreserveReferences(), answer.getPreserveReferences()));
        // A short answer fills a slot; an omitted/default empty pin list does not
        // authorize removing protections already saved by the server.
        answer.setPinnedStopIds(union(union(original.getPinnedStopIds(), answer.getPinnedStopIds()), context.getPinnedStopIds()));
        answer.setPreferences(union(original.getPreferences(), answer.getPreferences()));
        answer.setConditions(union(original.getConditions(), answer.getConditions()));
        if (answer.getTargetStartMinute() == null) { answer.setTargetStartMinute(original.getTargetStartMinute()); answer.setTargetEndMinute(original.getTargetEndMinute()); answer.setTimeScopeResolved(original.isTimeScopeResolved()); }
        state.pending = null;
        return answer;
    }
    private static String nextQuestion(String field, String fallback) {
        return switch (field) {
            case "DAY_NUMBER" -> "你想安排在第几天？";
            case "TIME_RANGE" -> "请明确一个有效的调整时段，例如某个具体时刻之后或一段明确起止时间；其余安排会保留。";
            case "TARGET_PLACE", "VENUE_OR_PREFERENCE" -> "请告诉我想要添加的具体地点或偏好。";
            case "TARGET_STOP" -> "请告诉我要调整的具体站点。";
            case "REPLAN_PREFERENCE" -> "请告诉我希望保留的兴趣或调整条件。";
            case "REDUCE_COUNT" -> "你希望减少多少个景点？";
            default -> fallback;
        };
    }
    private static ConversationIntentType operationType(String operation) {
        return switch (operation) {
            case "ADD_STOP" -> ConversationIntentType.ADD_STOP;
            case "REMOVE_STOP" -> ConversationIntentType.REMOVE_STOP;
            case "REPLACE_STOP", "SUGGEST_REPLACEMENTS" -> ConversationIntentType.SUGGEST_REPLACEMENTS;
            case "REPLAN_DAY" -> ConversationIntentType.REPLAN_DAY;
            case "REDUCE_DENSITY", "REDUCE_DAY_DENSITY" -> ConversationIntentType.REDUCE_DAY_DENSITY;
            default -> ConversationIntentType.UNKNOWN;
        };
    }
    private List<String> union(List<String> a, List<String> b) { var result = new ArrayList<String>(a == null ? List.of() : a); for (String v : b == null ? List.<String>of() : b) if (!result.contains(v)) result.add(v); return result; }
    private PlanAdjustmentIntent inheritProtections(PlanAdjustmentIntent original, PlanAdjustmentIntent answer, PlanPageContext context) {
        answer.setPreserveReferences(union(original.getPreserveReferences(), answer.getPreserveReferences()));
        answer.setPinnedStopIds(union(union(original.getPinnedStopIds(), answer.getPinnedStopIds()), context.getPinnedStopIds()));
        answer.setPreferences(union(original.getPreferences(), answer.getPreferences()));
        answer.setConditions(union(original.getConditions(), answer.getConditions()));
        classifier.resolveTimeScope(answer);
        if (answer.getTargetStartMinute() == null && !answer.getMissingFields().contains("TIME_RANGE")) {
            answer.setTargetStartMinute(original.getTargetStartMinute()); answer.setTargetEndMinute(original.getTargetEndMinute());
            answer.setTimeScopeResolved(original.getTargetStartMinute() != null || original.isTimeScopeResolved());
        }
        if (ConversationIntentClassifier.extractExplicitDayNumber(answer.getRawMessage()) == null)
            answer.setDayNumber(original.getDayNumber());
        return answer;
    }
    private boolean completeCommand(PlanAdjustmentIntent intent) {
        if (intent.getType() == ConversationIntentType.UNKNOWN
                || intent.isRequiresClarification() && (intent.getMissingFields().isEmpty()
                || intent.getMissingFields().stream().anyMatch(field -> !"TIME_RANGE".equals(field)))) return false;
        return switch (intent.getOperation()) {
            case "ADD_STOP" -> intent.getRequestedVenueName() != null || intent.getCuisineOrPlace() != null
                    || !intent.getPreferences().isEmpty();
            case "REMOVE_STOP" -> intent.getTargetStopId() != null || intent.getTargetStopReference() != null;
            case "REPLACE_STOP", "SUGGEST_REPLACEMENTS" -> intent.getReplacementPlaceName() != null
                    && (intent.getTargetStopReference() != null || intent.getTargetStopId() != null);
            case "REPLAN_DAY" -> !intent.getPreferences().isEmpty() || !intent.getConditions().isEmpty()
                    || intent.getCondition() != null || intent.getTargetStartMinute() != null;
            case "REDUCE_DENSITY", "REDUCE_DAY_DENSITY" -> intent.getReduceCount() != null && intent.getReduceCount() > 0;
            default -> false;
        };
    }
    public static boolean requestsDay(PlanAdjustmentIntent intent) { return intent.getMissingFields().stream().anyMatch(f -> f != null && Set.of("DAY","DAYNUMBER","TARGETDAY","TARGETDAYREFERENCE").contains(f.replace("_","").toUpperCase(Locale.ROOT))); }
    private void inferMissing(PlanAdjustmentIntent intent) {
        if (intent.getDayNumber() == null && !requestsDay(intent)) intent.setDayNumber(1);
        var canonical = new ArrayList<String>();
        for (String field : intent.getMissingFields()) {
            String key=field == null ? "" : field.replace("_","").toUpperCase(Locale.ROOT);
            String mapped=switch(key) {
                case "COMPOUNDACTIONORDER" -> "COMPOUND_ACTION_ORDER";
                case "DAY","DAYNUMBER","TARGETDAY","TARGETDAYREFERENCE" -> "DAY_NUMBER";
                case "TARGETPLACE","REQUESTEDVENUENAME","REPLACEMENTPLACENAME","VENUE" -> "TARGET_PLACE";
                case "TARGETSTOP","TARGETSTOPID","TARGETSTOPREFERENCE" -> "TARGET_STOP";
                case "VENUEORPREFERENCE" -> "VENUE_OR_PREFERENCE";
                case "REPLANPURPOSE" -> "REPLAN_PURPOSE";
                case "REPLANPREFERENCE","PREFERENCES","CONDITIONS" -> "REPLAN_PREFERENCE";
                case "TIMERANGE","TARGETSTARTMINUTE","TARGETENDMINUTE" -> "TIME_RANGE";
                case "REDUCECOUNT" -> "REDUCE_COUNT";
                case "REDUCTIONREPHRASE" -> "REDUCTION_REPHRASE";
                default -> "";
            };
            if (!mapped.isEmpty() && !canonical.contains(mapped)) canonical.add(mapped);
        }
        if (canonical.isEmpty()) canonical.addAll(switch(intent.getOperation()) {
            case "ADD_STOP" -> List.of("TARGET_PLACE"); case "REMOVE_STOP", "REPLACE_STOP", "SUGGEST_REPLACEMENTS" -> List.of("TARGET_STOP");
            case "REPLAN_DAY", "REDUCE_DENSITY", "REDUCE_DAY_DENSITY" -> List.of("REPLAN_PREFERENCE"); default -> List.of(); });
        intent.setMissingFields(canonical);
    }
}
