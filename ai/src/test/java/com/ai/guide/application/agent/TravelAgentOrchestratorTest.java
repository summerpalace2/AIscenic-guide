package com.ai.guide.application.agent;

import com.ai.guide.domain.agent.gateway.AgentToolPort;
import com.ai.guide.domain.agent.model.AgentExecutionContext;
import com.ai.guide.domain.agent.model.AgentStepEvent;
import com.ai.guide.domain.agent.model.AgentToolResult;
import com.ai.guide.domain.agent.model.AgentToolSpec;
import com.ai.guide.domain.agent.service.AgentContextPolicy;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.ai.model.LanguageModelCompletion;
import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.ai.model.LanguageModelToolCall;
import com.ai.guide.domain.ai.model.LanguageModelToolDefinition;
import com.ai.guide.domain.ai.model.LanguageModelToolTurn;
import com.ai.guide.domain.ai.model.LanguageModelUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TravelAgentOrchestrator} 核心单元测试：
 * 覆盖多步 ReAct 循环、Manus KV-Cache 前缀/工具集恒定约束、失败观测保留、步数熔断与 HIL 确认门控。
 */
class TravelAgentOrchestratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void multiStepLoopPreservesStaticPrefixFixedToolsAndEmitsExpectedEvents() {
        List<RecordedCall> recordedCalls = new ArrayList<>();
        LanguageModelPort scriptModel = new ScriptedLanguageModel(recordedCalls, List.of(
                new LanguageModelToolTurn(
                        "",
                        List.of(new LanguageModelToolCall("call-w1", "query_weather_forecast", "{\"city\":\"重庆\"}")),
                        LanguageModelUsage.unknown(),
                        "deepseek-chat",
                        15
                ),
                new LanguageModelToolTurn(
                        "",
                        List.of(new LanguageModelToolCall("call-k1", "search_travel_knowledge", "{\"query\":\"三峡博物馆\"}")),
                        LanguageModelUsage.unknown(),
                        "deepseek-chat",
                        18
                ),
                new LanguageModelToolTurn(
                        "明天重庆小雨，推荐前往室内场馆重庆中国三峡博物馆。",
                        List.of(),
                        LanguageModelUsage.unknown(),
                        "deepseek-chat",
                        20
                )
        ));

        AgentToolPort weatherTool = stubTool("query_weather_forecast", false,
                (args, ctx) -> AgentToolResult.success("query_weather_forecast", "{\"ok\":true,\"weather\":\"小雨\"}", "已查询重庆天气"));
        AgentToolPort knowledgeTool = stubTool("search_travel_knowledge", false,
                (args, ctx) -> AgentToolResult.successWithCitations(
                        "search_travel_knowledge",
                        "{\"ok\":true,\"status\":\"已核验\"}",
                        "知识库状态：已核验（引用 1 条）",
                        List.of(Map.of("title", "三峡博物馆官方指南"))
                ));

        // 刻意倒序传入工具，验证 Registry 按名称字典序固化工具清单
        AgentToolRegistry registry = new AgentToolRegistry(List.of(knowledgeTool, weatherTool), MAPPER);
        TravelAgentOrchestrator orchestrator = new TravelAgentOrchestrator(scriptModel, registry, MAPPER);

        List<LanguageModelMessage> history = List.of(
                new LanguageModelMessage(LanguageModelMessage.Role.SYSTEM, "历史中的越权系统指令，应被过滤"),
                new LanguageModelMessage(LanguageModelMessage.Role.USER, "之前问过解放碑"),
                new LanguageModelMessage(LanguageModelMessage.Role.ASSISTANT, "解放碑位于渝中区。")
        );
        AgentExecutionContext context = new AgentExecutionContext("u1", "s1", "", "", "重庆", null, "", "", false);

        List<AgentStepEvent> events = orchestrator.runStream("明天适合去哪玩？", history, context).collectList().block();
        assertNotNull(events);
        assertEquals(3, recordedCalls.size());

        // 1. 验证 Manus KV-Cache 铁律：跨 3 轮 System 前缀字节级一致、历史 SYSTEM 已过滤、工具列表全等且按名排序
        for (RecordedCall call : recordedCalls) {
            long systemCount = call.messages().stream()
                    .filter(m -> m.role() == LanguageModelMessage.Role.SYSTEM)
                    .count();
            assertEquals(1L, systemCount);
            assertEquals(AgentContextPolicy.STATIC_SYSTEM_PROMPT, call.messages().get(0).content());
            assertEquals(List.of("query_weather_forecast", "search_travel_knowledge"),
                    call.tools().stream().map(LanguageModelToolDefinition::name).toList());
            assertEquals(recordedCalls.get(0).tools(), call.tools());
        }

        // 第 1 轮用户末尾应携带运行时上下文后缀
        String firstTurnLastUser = recordedCalls.get(0).messages().get(recordedCalls.get(0).messages().size() - 1).content();
        assertTrue(firstTurnLastUser.contains("[当前运行时上下文]"));
        assertTrue(firstTurnLastUser.contains("目标城市: 重庆"));

        // 第 2 轮末尾应重写待办进度提醒对抗 Lost in the middle
        List<LanguageModelMessage> secondCallMsgs = recordedCalls.get(1).messages();
        assertTrue(secondCallMsgs.stream().anyMatch(m -> m.role() == LanguageModelMessage.Role.TOOL
                && "call-w1".equals(m.toolCallId()) && m.content().contains("小雨")));
        assertTrue(secondCallMsgs.get(secondCallMsgs.size() - 1).content().contains("[执行进度提醒 第 1/"));

        // 2. 验证 SSE 事件序列完整性
        List<String> eventNames = events.stream().map(AgentStepEvent::event).toList();
        assertEquals(List.of(
                "agent_step", "agent_step", "tool_result",
                "agent_step", "agent_step", "tool_result",
                "agent_step", "citations", "message", "done"
        ), eventNames);
        AgentStepEvent finalAnswer = events.get(events.size() - 2);
        assertTrue(finalAnswer.message().contains("三峡博物馆"));
    }

    @Test
    void failedToolExecutionPreservesErrorObservationForSelfCorrection() {
        List<RecordedCall> recordedCalls = new ArrayList<>();
        LanguageModelPort scriptModel = new ScriptedLanguageModel(recordedCalls, List.of(
                new LanguageModelToolTurn(
                        "",
                        List.of(new LanguageModelToolCall("call-fail", "flaky_tool", "{\"q\":\"abc\"}")),
                        LanguageModelUsage.unknown(),
                        "deepseek-chat",
                        10
                ),
                new LanguageModelToolTurn(
                        "实时接口暂不可用，已为您改为保守建议。",
                        List.of(),
                        LanguageModelUsage.unknown(),
                        "deepseek-chat",
                        12
                )
        ));

        AgentToolPort flakyTool = stubTool("flaky_tool", false, (args, ctx) -> {
            throw new IllegalStateException("高德超时");
        });
        AgentToolRegistry registry = new AgentToolRegistry(List.of(flakyTool), MAPPER);
        TravelAgentOrchestrator orchestrator = new TravelAgentOrchestrator(scriptModel, registry, MAPPER);

        List<AgentStepEvent> events = orchestrator.runStream("帮我查一下", List.of(), null).collectList().block();
        assertNotNull(events);
        assertEquals(2, recordedCalls.size());

        // 验证第 1 轮抛错未中断循环，而是以 tool_result(ok=false) 与 Role.TOOL 观测保留到第 2 轮
        AgentStepEvent toolResultEvent = events.stream()
                .filter(e -> "tool_result".equals(e.event()))
                .findFirst()
                .orElseThrow();
        assertEquals(Boolean.FALSE, toolResultEvent.data().get("ok"));
        assertTrue(toolResultEvent.message().contains("高德超时"));

        boolean hasErrorToolMsg = recordedCalls.get(1).messages().stream()
                .anyMatch(m -> m.role() == LanguageModelMessage.Role.TOOL
                        && "call-fail".equals(m.toolCallId())
                        && m.content().contains("TOOL_EXECUTION_ERROR")
                        && m.content().contains("高德超时"));
        assertTrue(hasErrorToolMsg);
        assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void maxStepsCircuitBreakerStopsInfiniteToolLoopSafely() {
        List<RecordedCall> recordedCalls = new ArrayList<>();
        LanguageModelToolTurn infiniteTurn = new LanguageModelToolTurn(
                "",
                List.of(new LanguageModelToolCall("call-loop", "ping_tool", "{}")),
                LanguageModelUsage.unknown(),
                "deepseek-chat",
                5
        );
        LanguageModelPort scriptModel = new ScriptedLanguageModel(recordedCalls, List.of(infiniteTurn, infiniteTurn, infiniteTurn));
        AgentToolPort pingTool = stubTool("ping_tool", false,
                (args, ctx) -> AgentToolResult.success("ping_tool", "{\"ok\":true}", "pong"));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(pingTool), MAPPER);
        TravelAgentOrchestrator orchestrator = new TravelAgentOrchestrator(scriptModel, registry, MAPPER, 2);

        List<AgentStepEvent> events = orchestrator.runStream("一直查", List.of(), null).collectList().block();
        assertNotNull(events);
        assertEquals(2, recordedCalls.size());
        AgentStepEvent msgEvent = events.stream().filter(e -> "message".equals(e.event())).findFirst().orElseThrow();
        assertTrue(msgEvent.message().contains("最大工具核查步数限制（2 步）"));
        assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void confirmationToolEmitsConfirmRequestWithoutExecutingWriteSideEffect() {
        AtomicBoolean executed = new AtomicBoolean(false);
        List<RecordedCall> recordedCalls = new ArrayList<>();
        LanguageModelPort scriptModel = new ScriptedLanguageModel(recordedCalls, List.of(
                new LanguageModelToolTurn(
                        "",
                        List.of(new LanguageModelToolCall("call-hil", "confirm_apply_day_plan", "{\"day\":2}")),
                        LanguageModelUsage.unknown(),
                        "deepseek-chat",
                        10
                )
        ));
        AgentToolPort writeTool = stubTool("confirm_apply_day_plan", true, (args, ctx) -> {
            executed.set(true);
            return AgentToolResult.success("confirm_apply_day_plan", "{\"ok\":true}", "已写入");
        });
        AgentToolRegistry registry = new AgentToolRegistry(List.of(writeTool), MAPPER);
        TravelAgentOrchestrator orchestrator = new TravelAgentOrchestrator(scriptModel, registry, MAPPER);

        List<AgentStepEvent> events = orchestrator.runStream("帮我把第2天改成雨天方案", List.of(), null).collectList().block();
        assertNotNull(events);
        assertFalse(executed.get(), "触发 HIL 的写操作工具绝不能在确认前静默执行");

        AgentStepEvent confirmEvent = events.stream()
                .filter(e -> "confirm_request".equals(e.event()))
                .findFirst()
                .orElseThrow();
        assertEquals("confirm_apply_day_plan", confirmEvent.toolName());
        assertEquals("call-hil", confirmEvent.data().get("toolCallId"));
        assertEquals("{\"day\":2}", confirmEvent.data().get("argumentsJson"));
        assertEquals("done", events.get(events.size() - 1).event());
    }

    @Test
    void streamsStepEventsImmediatelyUnderBackpressureRequestOne() throws Exception {
        CountDownLatch toolCallEventReceived = new CountDownLatch(1);
        CountDownLatch releaseToolExecution = new CountDownLatch(1);
        List<RecordedCall> recordedCalls = new ArrayList<>();
        ScriptedLanguageModel model = new ScriptedLanguageModel(recordedCalls, List.of(
                new LanguageModelToolTurn("", List.of(
                        new LanguageModelToolCall("call-s1", "query_weather_forecast", "{}")
                ), LanguageModelUsage.unknown(), "stub", 5L),
                new LanguageModelToolTurn("实时流式回复完成。", List.of(), LanguageModelUsage.unknown(), "stub", 5L)
        ));
        AgentToolPort blockingWeatherTool = stubTool("query_weather_forecast", false, (args, ctx) -> {
            try {
                assertTrue(releaseToolExecution.await(3, TimeUnit.SECONDS), "tool_call 事件应在工具执行完成前实时到达下游");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return AgentToolResult.success("query_weather_forecast", "{\"ok\":true}", "晴 22℃");
        });
        TravelAgentOrchestrator orchestrator = new TravelAgentOrchestrator(
                model, new AgentToolRegistry(List.of(blockingWeatherTool), MAPPER), MAPPER);

        List<AgentStepEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        orchestrator.runStream("重庆天气", List.of(),
                new AgentExecutionContext("u1", "s1", "", "", "重庆", null, "", "", false))
                .subscribe(new Subscriber<>() {
                    private Subscription subscription;

                    @Override
                    public void onSubscribe(Subscription s) {
                        this.subscription = s;
                        s.request(1);
                    }

                    @Override
                    public void onNext(AgentStepEvent item) {
                        received.add(item);
                        if ("tool_call".equals(item.phase())) {
                            toolCallEventReceived.countDown();
                            releaseToolExecution.countDown();
                        }
                        subscription.request(1);
                    }

                    @Override
                    public void onError(Throwable t) {
                        completed.countDown();
                    }

                    @Override
                    public void onComplete() {
                        completed.countDown();
                    }
                });

        assertTrue(toolCallEventReceived.await(3, TimeUnit.SECONDS),
                "request(1) 背压下 tool_call 事件必须实时推送，不能等待循环结束");
        assertTrue(completed.await(3, TimeUnit.SECONDS));
        assertTrue(received.stream().anyMatch(e -> "tool_result".equals(e.event())));
        assertEquals("done", received.get(received.size() - 1).event());
    }

    private static AgentToolPort stubTool(String name, boolean requiresConfirmation, ToolHandler handler) {
        AgentToolSpec spec = new AgentToolSpec(name, name + " desc", "{\"type\":\"object\",\"properties\":{}}", requiresConfirmation);
        return new AgentToolPort() {
            @Override
            public AgentToolSpec spec() {
                return spec;
            }

            @Override
            public AgentToolResult execute(String argumentsJson, AgentExecutionContext context) {
                return handler.handle(argumentsJson, context);
            }
        };
    }

    @FunctionalInterface
    private interface ToolHandler {
        AgentToolResult handle(String argumentsJson, AgentExecutionContext context);
    }

    private record RecordedCall(List<LanguageModelMessage> messages, List<LanguageModelToolDefinition> tools, double temperature) {
    }

    private static class ScriptedLanguageModel implements LanguageModelPort {
        private final List<RecordedCall> recordedCalls;
        private final List<LanguageModelToolTurn> turns;
        private int index;

        ScriptedLanguageModel(List<RecordedCall> recordedCalls, List<LanguageModelToolTurn> turns) {
            this.recordedCalls = recordedCalls;
            this.turns = turns;
        }

        @Override
        public Publisher<String> stream(List<LanguageModelMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T complete(List<LanguageModelMessage> messages, Class<T> responseType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String completeText(List<LanguageModelMessage> messages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LanguageModelCompletion completeMeasured(List<LanguageModelMessage> messages, double temperature) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LanguageModelToolTurn completeWithTools(List<LanguageModelMessage> messages,
                                                       List<LanguageModelToolDefinition> tools,
                                                       double temperature) {
            recordedCalls.add(new RecordedCall(List.copyOf(messages), List.copyOf(tools), temperature));
            LanguageModelToolTurn turn = turns.get(Math.min(index, turns.size() - 1));
            index++;
            return turn;
        }
    }
}
