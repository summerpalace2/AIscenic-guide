package com.ai.guide.domain.planner.service;

import com.ai.guide.domain.planner.model.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Server-owned expiring continuation: a short answer fills only the requested slot. */
public final class PendingIntentContinuations {
    public record Pending(String intentId, int baseRevision, long expiresAt, PlanAdjustmentIntent intent) {}
    private final Map<String, Pending> entries = new LinkedHashMap<>();
    private final Map<String, PlanAdjustmentIntent> remainingActions = new LinkedHashMap<>();
    private final Map<String, String> generations = new LinkedHashMap<>();
    private final Map<String, String> epochs = new LinkedHashMap<>();
    private final Map<String, Long> resetAt = new LinkedHashMap<>();
    private final LongSupplier clock;
    public synchronized String begin(String session) {
        if (generations.size() >= 4096 && !generations.containsKey(session)) {
            String oldest = generations.keySet().iterator().next();
            generations.remove(oldest); resetAt.remove(oldest); epochs.remove(oldest);
            entries.remove(oldest); remainingActions.remove(oldest);
        }
        // The first proposal also has a random epoch. Eviction may forget this
        // authority; it must never recreate a shared INITIAL value for old proposals.
        epochs.computeIfAbsent(session, ignored -> UUID.randomUUID().toString());
        String ticket = UUID.randomUUID().toString(); generations.put(session, ticket); return ticket;
    }
    public synchronized boolean current(String session, String ticket) { return Objects.equals(generations.get(session), ticket); }
    public synchronized boolean commit(String session, String ticket, Runnable action) { if (!current(session,ticket)) return false; action.run(); return true; }
    public synchronized void reset(String session) { begin(session); clear(session); remainingActions.remove(session); epochs.put(session,UUID.randomUUID().toString()); resetAt.put(session, clock.getAsLong()); }
    public synchronized void afterApply(String session, int revision) {
        var remaining = remainingActions.get(session);
        reset(session);
        if (remaining != null && !remaining.getCompoundActions().isEmpty()) remember(session, revision, remaining);
    }
    public synchronized String epoch(String session) { return epochs.getOrDefault(session, ""); }
    public synchronized boolean validProposal(String session, String proposalEpoch, long createdAt) {
        String currentEpoch = epochs.get(session);
        return currentEpoch != null && proposalEpoch != null && !proposalEpoch.isBlank()
                && !"INITIAL".equals(proposalEpoch) && currentEpoch.equals(proposalEpoch);
    }
    /** Legacy proposals lack an authoritative epoch and must be previewed again. */
    public synchronized boolean validProposal(String session, long createdAt) { return false; }
    private final ConversationIntentClassifier classifier = new ConversationIntentClassifier();
    public PendingIntentContinuations() { this(System::currentTimeMillis); }
    public PendingIntentContinuations(LongSupplier clock) { this.clock = clock; }
    public synchronized void clear(String session) { entries.remove(session); }
    public synchronized Pending pending(String session) { return entries.get(session); }
    public synchronized void abandonCompoundQueue(String session) { remainingActions.remove(session); }
    public synchronized Pending remember(String session, int revision, PlanAdjustmentIntent intent) {
        entries.entrySet().removeIf(e -> e.getValue().expiresAt() <= clock.getAsLong());
        if (entries.size() >= 1024) entries.remove(entries.keySet().iterator().next());
        inferMissing(intent);
        var pending = new Pending(UUID.randomUUID().toString(), revision, clock.getAsLong() + 600_000, intent);
        entries.put(session, pending); return pending;
    }
    public synchronized PlanAdjustmentIntent resume(String session, int revision, String id, String message, PlanPageContext context, String ticket) {
        return current(session,ticket) ? resume(session,revision,id,message,context) : null;
    }
    public synchronized PlanAdjustmentIntent resume(String session, int revision, String id, String message, PlanPageContext context) {
        Pending pending = entries.get(session);
        if (pending == null) return null;
        if (pending.baseRevision() != revision || pending.expiresAt() <= clock.getAsLong()) { entries.remove(session); return null; }
        if (id != null && !id.isBlank() && !id.equals(pending.intentId())) return null;
        String text = message == null ? "" : message.trim();
        PlanAdjustmentIntent original = pending.intent();
        String field = original.getMissingFields().isEmpty() ? "" : original.getMissingFields().get(0);
        if ("COMPOUND_ACTION_ORDER".equals(field)) {
            if (IntentLanguagePolicy.positiveText(text).matches(".*(?:取消|停止|重新开始).*")) { entries.remove(session); remainingActions.remove(session); return null; }
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
                if (acceptedActions.isEmpty()) { entries.remove(session); remainingActions.remove(session); return null; }
                return original.toBuilder().compoundActions(acceptedActions)
                        .clarificationQuestion("已移除被拒绝的操作。剩余要求：\n" + String.join("\n", acceptedActions)
                                + "\n请选择希望执行的项，或发送新的完整调整要求。").build();
            }
            var standalone = classifier.classify(positive, context);
            if (positive.matches("(?s).*(?:添加|加入|删除|移除|替换|换成|重排|重新规划|优化).*")) {
                entries.remove(session); remainingActions.remove(session);
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
                    entries.remove(session); remainingActions.remove(session); return inheritProtections(original, standalone, context);
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
            AdjustmentTimeScopeResolver.resolve(answer, null);
            if (answer.getTargetStartMinute() == null && !answer.getMissingFields().contains("TIME_RANGE")) {
                answer.setTargetStartMinute(original.getTargetStartMinute());
                answer.setTargetEndMinute(original.getTargetEndMinute());
                answer.setTimeScopeResolved(original.getTargetStartMinute() != null || original.isTimeScopeResolved());
            }
            var remaining = new ArrayList<>(acceptedActions); remaining.remove(actions.get(index - 1));
            if (!remaining.isEmpty()) {
                if (remainingActions.size() >= 1024 && !remainingActions.containsKey(session)) remainingActions.remove(remainingActions.keySet().iterator().next());
                remainingActions.put(session, original.toBuilder().compoundActions(remaining)
                        .clarificationQuestion("剩余要求尚未执行：\n" + String.join("\n", remaining) + "\n回复“执行下一个”或选择序号，逐项查看预览。").build());
            } else remainingActions.remove(session);
            entries.remove(session);
            return answer;
        }
        Integer statedDay = ConversationIntentClassifier.extractExplicitDayNumber(text);
        var explicit = classifier.classify(text,context);
        boolean explicitCommand = text.matches("(?s).*(?:添加|加入|删除|移除|替换|换成|重排|重新规划|优化).*" );
        if (explicitCommand && Objects.equals(explicit.getOperation(), original.getOperation()) && completeCommand(explicit)) {
            entries.remove(session); remainingActions.remove(session);
            return inheritProtections(original, explicit, context);
        }
        if (IntentLanguagePolicy.positiveText(text).matches("(?s).*(?:取消|停止|重新开始).*")
                || (!"DAY_NUMBER".equals(field) && statedDay != null && !Objects.equals(statedDay, original.getDayNumber()))
                || (explicitCommand && explicit.getOperation() != null && !Set.of("UNKNOWN","CLARIFICATION").contains(explicit.getOperation())
                    && !Objects.equals(explicit.getOperation(),original.getOperation()))) {
            entries.remove(session); return null;
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
        } else if ("REPLAN_PURPOSE".equals(field)) {
            Integer choice = ConversationIntentClassifier.parseChineseNumber(text.replaceAll("^(?:选|选择|第)|个$|项$", ""));
            String op = choice == null ? "" : switch(choice) { case 1 -> "REPLAN_DAY"; case 2 -> "REPLACE_STOP"; case 3 -> "REDUCE_DENSITY"; case 4 -> "ADD_STOP"; default -> ""; };
            if (op.isEmpty()) return original;
            answer = PlanAdjustmentIntent.builder().type(ConversationIntentType.CLARIFICATION).operation(op).scope(choice == 2 ? "STOP" : "DAY")
                    .dayNumber(day).requiresClarification(true).rawMessage(original.getRawMessage()).build();
            if (choice == 3) { answer.setType(ConversationIntentType.REDUCE_DAY_DENSITY); answer.setReduceCount(1); answer.setRequiresClarification(false); }
            else {
                answer.setMissingFields(List.of(choice == 1 ? "REPLAN_PREFERENCE" : choice == 2 ? "TARGET_STOP" : "TARGET_PLACE"));
                answer.setClarificationQuestion(choice == 1 ? "你想怎样重新规划第 " + day + " 天？请告诉我希望保留的兴趣或调整条件。"
                        : "请告诉我第 " + day + " 天要" + (choice == 2 ? "替换" : "添加") + "的具体地点。");
            }
        } else if ("TIME_RANGE".equals(field)) {
            // Parse the correction alone: old invalid clocks are evidence, not execution input.
            var corrected = original.toBuilder().rawMessage(text).targetStartMinute(null).targetEndMinute(null)
                    .timeScopeResolved(false).requiresClarification(false).missingFields(new ArrayList<>()).build();
            AdjustmentTimeScopeResolver.resolve(corrected, null);
            if (corrected.isRequiresClarification() || corrected.getTargetStartMinute() == null) return original;
            var missing = new ArrayList<>(original.getMissingFields()); missing.remove("TIME_RANGE");
            answer = original.toBuilder().targetStartMinute(corrected.getTargetStartMinute()).targetEndMinute(corrected.getTargetEndMinute())
                    .timeScopeResolved(true).missingFields(missing).requiresClarification(!missing.isEmpty())
                    .clarificationQuestion(missing.isEmpty() ? null : original.getClarificationQuestion())
                    .type(missing.isEmpty() ? operationType(original.getOperation()) : ConversationIntentType.CLARIFICATION).build();
        } else {
            String prefix = switch(field) { case "TARGET_PLACE", "VENUE_OR_PREFERENCE" -> "添加"; case "TARGET_STOP" -> "REMOVE_STOP".equals(original.getOperation()) ? "删除" : "替换";
                case "REPLAN_PREFERENCE", "TIME_RANGE" -> "重新规划行程，"; default -> ""; };
            if (prefix.isEmpty()) return null;
            String slotText = IntentLanguagePolicy.positiveText(text);
            answer = classifier.classify(prefix + (Set.of("TARGET_PLACE", "VENUE_OR_PREFERENCE").contains(field) ? slotText.replaceFirst("^(?:我)?(?:想去|要去|希望去|想添加|添加|加入|去)\\s*", "") : slotText), answerContext);
            if ("TARGET_STOP".equals(field)) {
                if (original.getRequestedVenueName() != null) answer.setRequestedVenueName(original.getRequestedVenueName());
                if (original.getReplacementPlaceName() != null) answer.setReplacementPlaceName(original.getReplacementPlaceName());
                if (original.getReplacementPlaceId() != null) answer.setReplacementPlaceId(original.getReplacementPlaceId());
                if (original.getTargetAttractionId() != null) answer.setTargetAttractionId(original.getTargetAttractionId());
                answer.setOperation(original.getOperation());
                if (answer.getTargetStopId() != null || answer.getTargetStopReference() != null) {
                    var missing = new ArrayList<>(original.getMissingFields()); missing.remove("TARGET_STOP");
                    answer.setMissingFields(missing); answer.setRequiresClarification(!missing.isEmpty());
                    answer.setType(missing.isEmpty() ? operationType(original.getOperation()) : ConversationIntentType.CLARIFICATION);
                    answer.setClarificationQuestion(missing.isEmpty() ? null : original.getClarificationQuestion());
                }
            }
            if ("REPLAN_PREFERENCE".equals(field) && answer.getPreferences().isEmpty() && answer.getConditions().isEmpty()) {
                answer.setType(ConversationIntentType.CLARIFICATION); answer.setOperation(original.getOperation()); answer.setRequiresClarification(true); answer.setMissingFields(original.getMissingFields()); answer.setClarificationQuestion(original.getClarificationQuestion());
            }
            if (Set.of("TARGET_PLACE", "VENUE_OR_PREFERENCE", "REPLAN_PREFERENCE").contains(field)
                    && !answer.isRequiresClarification() && answer.getType() != ConversationIntentType.UNKNOWN) {
                // A parsed slot answer is not a replacement for the whole pending
                // task. Other required fields remain required regardless of order.
                var missing = new ArrayList<>(original.getMissingFields());
                missing.remove(field);
                answer.setOperation(original.getOperation());
                answer.setMissingFields(missing);
                answer.setRequiresClarification(!missing.isEmpty());
                answer.setType(missing.isEmpty() ? operationType(original.getOperation()) : ConversationIntentType.CLARIFICATION);
                answer.setClarificationQuestion(missing.isEmpty() ? null : nextQuestion(missing.get(0), original.getClarificationQuestion()));
            }
        }
        answer.setDayNumber(day);
        answer.setRawMessage(original.getRawMessage() + "，" + text);
        answer.setPreserveReferences(union(original.getPreserveReferences(), answer.getPreserveReferences()));
        // A short answer fills a slot; an omitted/default empty pin list does not
        // authorize removing protections already saved by the server.
        answer.setPinnedStopIds(union(union(original.getPinnedStopIds(), answer.getPinnedStopIds()), context.getPinnedStopIds()));
        answer.setPreferences(union(original.getPreferences(), answer.getPreferences()));
        answer.setConditions(union(original.getConditions(), answer.getConditions()));
        if (answer.getTargetStartMinute() == null) { answer.setTargetStartMinute(original.getTargetStartMinute()); answer.setTargetEndMinute(original.getTargetEndMinute()); answer.setTimeScopeResolved(original.isTimeScopeResolved()); }
        entries.remove(session);
        return answer;
    }
    private static String nextQuestion(String field, String fallback) {
        return switch (field) {
            case "DAY_NUMBER" -> "你想安排在第几天？";
            case "TIME_RANGE" -> "请明确一个有效的调整时段，例如18:00以后或20:00至21:00；其余安排会保留。";
            case "TARGET_PLACE", "VENUE_OR_PREFERENCE" -> "请告诉我想要添加的具体地点或偏好。";
            case "TARGET_STOP" -> "请告诉我要调整的具体站点。";
            case "REPLAN_PREFERENCE" -> "请告诉我希望保留的兴趣或调整条件。";
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
        AdjustmentTimeScopeResolver.resolve(answer, null);
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
