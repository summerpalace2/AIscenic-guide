package com.ai.guide.application.agent;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.agent.gateway.AgentConfirmationPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentPendingConfirmation;
import com.ai.guide.domain.agent.model.AgentStepEvent;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.agent.service.AgentContextPolicy;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.ai.model.LanguageModelToolCall;
import com.ai.guide.domain.ai.model.LanguageModelToolDefinition;
import com.ai.guide.domain.ai.model.LanguageModelToolTurn;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 文旅智能体统一编排器（Single Orchestrator + Bounded ReAct Loop）。
 *
 * <p>架构定位：COLA 应用层（application.agent）。
 * 运行时严格遵守上下文工程 5 项规范：
 * <ol>
 *   <li>静态系统提示词 {@link AgentContextPolicy#STATIC_SYSTEM_PROMPT} 始终置顶且字节不变，保护 KV Cache；</li>
 *   <li>每轮传入完全一致的固定顺序工具全集 {@link AgentToolRegistry#toolDefinitions()}；</li>
 *   <li>动态视图上下文仅追加在初始用户消息末尾；</li>
 *   <li>多步工具执行时保留所有调用与失败观测，并在最新轮次追加待办进度提醒对抗 Lost in the middle；</li>
 *   <li>命中需人工确认（HIL）的写操作工具时通过 {@link AgentConfirmationPort} 登记挂起单，阻断静默执行并向前端发出确认请求事件。</li>
 * </ol>
 */
@Service
public class TravelAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TravelAgentOrchestrator.class);
    private static final double DEFAULT_TEMPERATURE = 0.2d;
    private static final int LLM_MAX_ATTEMPTS = 3;
    private static final long LLM_RETRY_BACKOFF_MS = 250L;
    private static final long LLM_RETRY_JITTER_MS = 120L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /** 单轮内允许按参数完全一致去重复用的纯只读查询工具 */
    private static final Set<String> READ_ONLY_DEDUP_TOOLS = Set.of(
            "get_current_trip_draft",
            "get_user_preferences",
            "query_weather_forecast",
            "search_poi_and_dining",
            "calculate_route_matrix",
            "search_travel_knowledge"
    );

    private final LanguageModelPort languageModel;
    private final AgentToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final AgentConfirmationPort confirmationPort;
    private final int maxSteps;

    public TravelAgentOrchestrator(LanguageModelPort languageModel,
                                   AgentToolRegistry toolRegistry,
                                   ObjectMapper objectMapper) {
        this(languageModel, toolRegistry, objectMapper, null, AgentContextPolicy.DEFAULT_MAX_STEPS);
    }

    public TravelAgentOrchestrator(LanguageModelPort languageModel,
                                   AgentToolRegistry toolRegistry,
                                   ObjectMapper objectMapper,
                                   int maxSteps) {
        this(languageModel, toolRegistry, objectMapper, null, maxSteps);
    }

    @Autowired
    public TravelAgentOrchestrator(LanguageModelPort languageModel,
                                   AgentToolRegistry toolRegistry,
                                   ObjectMapper objectMapper,
                                   AgentConfirmationPort confirmationPort) {
        this(languageModel, toolRegistry, objectMapper, confirmationPort, AgentContextPolicy.DEFAULT_MAX_STEPS);
    }

    public TravelAgentOrchestrator(LanguageModelPort languageModel,
                                   AgentToolRegistry toolRegistry,
                                   ObjectMapper objectMapper,
                                   AgentConfirmationPort confirmationPort,
                                   int maxSteps) {
        this.languageModel = languageModel;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.confirmationPort = confirmationPort;
        this.maxSteps = Math.max(1, maxSteps);
    }

    /**
     * 以流式事件方式执行一次完整的智能体思考—工具调用—回答闭环。
     */
    public Flux<AgentStepEvent> runStream(String userMessage,
                                          List<LanguageModelMessage> historyMessages,
                                          AgentExecutionContext context) {
        return Flux.<AgentStepEvent>create(sink -> {
            String prevUserId = UserContext.getUserId();
            String prevUsername = UserContext.getUsername();
            String prevRole = UserContext.getRole();
            try {
                bindWorkerUserContext(context);
                executeLoop(userMessage, historyMessages, context, sink::next, sink::isCancelled);
                if (!sink.isCancelled()) {
                    sink.complete();
                }
            } catch (Exception ex) {
                log.warn("[TravelAgent] 编排执行异常: {}", ex.getMessage(), ex);
                if (!sink.isCancelled()) {
                    sink.next(AgentStepEvent.error("智能体执行遇到异常：" + safeError(ex)));
                    sink.next(AgentStepEvent.done());
                    sink.complete();
                }
            } finally {
                restoreWorkerUserContext(prevUserId, prevUsername, prevRole);
            }
        // requestOn=false：避免 Spring MVC ReactiveTypeHandler 的逐条 request(1) 被排入执行循环所在的单线程 Worker 队列，
        // 确保每条 agent_step / tool_result 实时刷新到 SSE 客户端而不是积压到循环结束。
        }).subscribeOn(Schedulers.boundedElastic(), false);
    }

    private void executeLoop(String userMessage,
                             List<LanguageModelMessage> historyMessages,
                             AgentExecutionContext context,
                             java.util.function.Consumer<AgentStepEvent> emitter,
                             java.util.function.BooleanSupplier cancelled) {
        AgentExecutionContext effectiveContext = context == null
                ? new AgentExecutionContext("", "", "", "", "重庆", null, "", "", false)
                : context;

        List<LanguageModelMessage> conversation = buildInitialMessages(userMessage, historyMessages, effectiveContext);
        List<LanguageModelToolDefinition> fixedTools = toolRegistry.toolDefinitions();
        List<String> completedActions = new ArrayList<>();
        Set<Object> collectedCitations = new LinkedHashSet<>();
        Map<String, Object> previewStopChangesByProposalId = new LinkedHashMap<>();
        Map<String, String> readOnlyCallCache = new LinkedHashMap<>();

        for (int step = 1; step <= maxSteps; step++) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            emitter.accept(AgentStepEvent.agentStep(step, "reasoning", "", "正在分析问题与规划核查步骤…"));
            LanguageModelToolTurn turn = completeWithToolsWithRetry(step, conversation, fixedTools, emitter, cancelled);
            if (cancelled.getAsBoolean()) {
                return;
            }

            if (!turn.hasToolCalls()) {
                if (!collectedCitations.isEmpty()) {
                    emitter.accept(AgentStepEvent.citations(new ArrayList<>(collectedCitations)));
                }
                String answer = turn.textContent().isBlank()
                        ? "已完成信息查证，但模型未生成有效文字回复，请补充或重试。"
                        : turn.textContent();
                emitter.accept(AgentStepEvent.textDelta(answer));
                emitter.accept(AgentStepEvent.done());
                return;
            }

            // 必须先完整追加助手发起 toolCalls 的消息，再依次追加对应 TOOL 结果，维持会话协议与前缀只追加特性
            conversation.add(LanguageModelMessage.assistantToolCalls(turn.textContent(), turn.toolCalls()));

            for (LanguageModelToolCall call : turn.toolCalls()) {
                if (cancelled.getAsBoolean()) {
                    return;
                }
                AgentToolSpec spec = toolRegistry.specOf(call.name()).orElse(null);
                if (spec != null && spec.requiresConfirmation()) {
                    if (!collectedCitations.isEmpty()) {
                        emitter.accept(AgentStepEvent.citations(new ArrayList<>(collectedCitations)));
                    }
                    emitter.accept(buildConfirmEvent(step, call, effectiveContext, previewStopChangesByProposalId));
                    emitter.accept(AgentStepEvent.done());
                    return;
                }

                // 单轮内重复调用完全一致的只读查询时直接复用已得观测结果，避免无效外呼与重复胶囊
                String dedupKey = READ_ONLY_DEDUP_TOOLS.contains(call.name())
                        ? canonicalToolCacheKey(call.name(), call.argumentsJson())
                        : null;
                if (dedupKey != null && readOnlyCallCache.containsKey(dedupKey)) {
                    log.debug("[TravelAgent] 复用单轮内已成功执行的只读工具结果 tool={}", call.name());
                    conversation.add(LanguageModelMessage.toolResult(call.id(), call.name(), readOnlyCallCache.get(dedupKey)));
                    continue;
                }

                emitter.accept(AgentStepEvent.agentStep(step, "tool_call", call.name(), "正在调用工具：" + call.name()));
                AgentToolResult result = toolRegistry.execute(call.name(), call.argumentsJson(), effectiveContext);

                if (result.ok() && "preview_trip_adjustment".equals(call.name())) {
                    capturePreviewStopChanges(result.jsonPayload(), previewStopChangesByProposalId);
                }
                if (result.ok() && dedupKey != null) {
                    readOnlyCallCache.put(dedupKey, result.jsonPayload());
                }

                if (result.citations() != null && !result.citations().isEmpty()) {
                    collectedCitations.addAll(result.citations());
                }
                emitter.accept(AgentStepEvent.toolResult(
                        step, call.name(), result.ok(), result.summary(), result.clientData()));

                // 若本步工具（如 create_trip_plan）在同一轮内创建了新的规划会话，则同步更新后续步的运行时上下文并清空草稿读缓存
                if (result.ok() && result.clientData() != null
                        && result.clientData().get("plannerSessionId") instanceof String createdSessionId
                        && !createdSessionId.isBlank()) {
                    readOnlyCallCache.keySet().removeIf(k -> k.startsWith("get_current_trip_draft:"));
                    Integer nextRevision = result.clientData().get("currentVersion") instanceof Number n
                            ? n.intValue()
                            : 1;
                    effectiveContext = new AgentExecutionContext(
                            effectiveContext.userId(),
                            effectiveContext.anonymous(),
                            effectiveContext.sessionId(),
                            createdSessionId,
                            effectiveContext.plannerAccessToken(),
                            effectiveContext.tripId(),
                            nextRevision,
                            effectiveContext.activeCity(),
                            effectiveContext.activeDay(),
                            effectiveContext.activeStopId(),
                            effectiveContext.activeStopName(),
                            effectiveContext.deepMode()
                    );
                }

                // 无论成功或失败，均保留真实 ToolResult 供模型在下一轮修正决策
                conversation.add(LanguageModelMessage.toolResult(call.id(), call.name(), result.jsonPayload()));
                completedActions.add(call.name() + "（" + (result.ok() ? "成功" : "失败") + "）");
            }

            // 多步执行中在上下文末尾重写进度提醒，对抗长上下文目标遗忘
            conversation.add(new LanguageModelMessage(
                    LanguageModelMessage.Role.USER,
                    AgentContextPolicy.buildLoopChecklistReminder(step, maxSteps, completedActions)
            ));
        }

        // 达到最大步数熔断：保留已收集到的核验来源并给出安全兜底提示
        if (!collectedCitations.isEmpty()) {
            emitter.accept(AgentStepEvent.citations(new ArrayList<>(collectedCitations)));
        }
        emitter.accept(AgentStepEvent.textDelta("已达到单轮最大工具核查步数限制（" + maxSteps + " 步），请缩小问题范围或基于当前信息继续追问。"));
        emitter.accept(AgentStepEvent.done());
    }

    private AgentStepEvent buildConfirmEvent(int step,
                                             LanguageModelToolCall call,
                                             AgentExecutionContext effectiveContext,
                                             Map<String, Object> previewStopChangesByProposalId) {
        Map<String, Object> parsedArgs = new LinkedHashMap<>(parseArgsSafe(call.argumentsJson()));
        if ("confirm_apply_trip_adjustment".equals(call.name()) && previewStopChangesByProposalId != null) {
            Object proposalId = parsedArgs.get("proposalId");
            if (proposalId != null) {
                Object changes = previewStopChangesByProposalId.get(String.valueOf(proposalId));
                if (changes != null) {
                    parsedArgs.put("stopChanges", changes);
                }
            }
        }
        Object rawSummary = parsedArgs.get("changeSummary");
        String summary = (rawSummary != null && !String.valueOf(rawSummary).isBlank())
                ? String.valueOf(rawSummary).trim()
                : "以下操作涉及修改行程或个人偏好，请确认后执行。";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tool", call.name());
        payload.put("toolCallId", call.id());
        payload.put("argumentsJson", call.argumentsJson());
        payload.put("args", parsedArgs);
        payload.put("summary", summary);

        if (confirmationPort != null) {
            AgentPendingConfirmation pending = confirmationPort.createPending(
                    call.name(), call.id(), call.argumentsJson(), summary, payload, effectiveContext);
            payload.put("confirmId", pending.confirmId());
            payload.put("expiresAtMillis", pending.expiresAtMillis());
        } else {
            payload.put("confirmId", "cfg_" + UUID.randomUUID().toString().replace("-", ""));
        }
        return AgentStepEvent.confirmRequest(step, call.name(), summary, payload);
    }

    private LanguageModelToolTurn completeWithToolsWithRetry(
            int step,
            List<LanguageModelMessage> conversation,
            List<LanguageModelToolDefinition> fixedTools,
            java.util.function.Consumer<AgentStepEvent> emitter,
            java.util.function.BooleanSupplier cancelled) {
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= LLM_MAX_ATTEMPTS; attempt++) {
            if (cancelled.getAsBoolean()) {
                throw new IllegalStateException("请求已取消");
            }
            try {
                return languageModel.completeWithTools(conversation, fixedTools, DEFAULT_TEMPERATURE);
            } catch (IllegalArgumentException | UnsupportedOperationException nonRetryable) {
                throw nonRetryable;
            } catch (RuntimeException ex) {
                lastError = ex;
                log.warn("[TravelAgent] 模型调用异常 step={} attempt={}/{}: {}",
                        step, attempt, LLM_MAX_ATTEMPTS, ex.getMessage());
                if (attempt >= LLM_MAX_ATTEMPTS || cancelled.getAsBoolean()) {
                    break;
                }
                emitter.accept(AgentStepEvent.agentStep(
                        step, "reasoning", "", "模型响应波动，正在自动重试（第 " + attempt + " 次）…"));
                try {
                    long jitterMs = ThreadLocalRandom.current().nextLong(0L, LLM_RETRY_JITTER_MS);
                    Thread.sleep(LLM_RETRY_BACKOFF_MS * attempt + jitterMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw lastError != null ? lastError : new IllegalStateException("模型未返回有效响应");
    }

    private String canonicalToolCacheKey(String toolName, String argumentsJson) {
        Map<String, Object> parsed = parseArgsSafe(argumentsJson);
        if (parsed.isEmpty()) {
            return toolName + ":{}";
        }
        try {
            return toolName + ":" + objectMapper.writeValueAsString(new TreeMap<>(parsed));
        } catch (Exception ignored) {
            return toolName + ":" + (argumentsJson == null ? "" : argumentsJson.trim());
        }
    }

    private void capturePreviewStopChanges(String jsonPayload, Map<String, Object> target) {
        if (jsonPayload == null || jsonPayload.isBlank() || target == null) {
            return;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(jsonPayload, MAP_TYPE);
            if (parsed == null) return;
            Object proposalId = parsed.get("proposalId");
            Object stopChanges = parsed.get("stopChanges");
            if (proposalId != null && stopChanges instanceof List<?> list && !list.isEmpty()) {
                target.put(String.valueOf(proposalId), list);
            }
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> parseArgsSafe(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(argumentsJson, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private List<LanguageModelMessage> buildInitialMessages(String userMessage,
                                                              List<LanguageModelMessage> historyMessages,
                                                              AgentExecutionContext context) {
        List<LanguageModelMessage> messages = new ArrayList<>();
        // 1. 固定不变的系统提示词前缀
        messages.add(new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, AgentContextPolicy.STATIC_SYSTEM_PROMPT));
        // 2. 过滤历史中的 SYSTEM 消息，防止历史消息插队污染前缀缓存
        if (historyMessages != null) {
            for (LanguageModelMessage msg : historyMessages) {
                if (msg != null && msg.role() != LanguageModelMessage.Role.SYSTEM) {
                    messages.add(msg);
                }
            }
        }
        // 3. 将动态运行时上下文追加在最新用户消息末尾
        messages.add(new LanguageModelMessage(
                LanguageModelMessage.Role.USER,
                AgentContextPolicy.buildDynamicContextSuffix(context, userMessage)
        ));
        return messages;
    }

    private static void bindWorkerUserContext(AgentExecutionContext context) {
        if (context != null && !context.anonymous() && !context.userId().isBlank()) {
            UserContext.set(context.userId(), UserContext.getUsername(), UserContext.getRole());
        } else if (context != null && context.anonymous()) {
            UserContext.clear();
        }
    }

    private static void restoreWorkerUserContext(String prevUserId, String prevUsername, String prevRole) {
        if (prevUserId == null) {
            UserContext.clear();
        } else {
            UserContext.set(prevUserId, prevUsername, prevRole);
        }
    }

    private static String safeError(Exception ex) {
        return (ex.getMessage() == null || ex.getMessage().isBlank())
                ? ex.getClass().getSimpleName()
                : ex.getMessage();
    }
}
