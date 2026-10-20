package com.ai.guide.infrastructure.planner.proposal;

import com.ai.guide.application.chat.*;
import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import com.ai.guide.domain.chat.gateway.ChatConversationMemoryPort;
import com.ai.guide.domain.chat.service.*;
import com.ai.guide.domain.analytics.service.SentimentService;
import com.ai.guide.domain.taxonomy.gateway.TaxonomyPort;
import com.ai.guide.infrastructure.chat.history.RedisChatHistoryStoreAdapter;
import com.ai.guide.infrastructure.chat.memory.RedisChatMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real isolated Redis, independent memory adapters and a deliberately parked writer/compressor. */
@EnabledIfEnvironmentVariable(named="CP6_IT_ENABLE",matches="true")
class Cp6RealChatMemoryIntegrationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedisChatMemory first,second;
    private QueuedExecutor writer,compressor;
    private String user,key;
    @BeforeEach void setup() {
        var target = new Cp6InfrastructureTarget(System.getenv());
        factory=target.redisFactory();redis=Cp6InfrastructureTarget.redis(factory);
        user="cp6-chat-"+UUID.randomUUID(); key=user+":turn";
        UserContext.set(user,"USER");writer=new QueuedExecutor();compressor=new QueuedExecutor();
        // Match Spring Boot's application ObjectMapper policy for the Spring-AI Message projection.
        var mapper=new ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,false);
        first=new RedisChatMemory(redis,mapper,writer);
        second=new RedisChatMemory(redis,mapper,new QueuedExecutor());
    }
    @AfterEach void cleanup() {
        writer.shutdownNow();compressor.shutdownNow();
        redis.delete(List.of("chat:history:"+key,"chat:sessions:"+user,key+":summary_snapshot",key+":summary",
                key+":compressed_count",key+":compress_count",key+":write_epoch"));
        factory.destroy();UserContext.clear();
    }
    @Test void completedProviderStreamIsImmediatelyVisibleToAnotherAdapterWithWriterParked() {
        var events = adapt(Flux.just("已完成回复锚点")).collectList().block();
        assertNotNull(events);
        assertTrue(events.stream().anyMatch(event -> "text".equals(event.event())));
        var history=second.loadHistory(key,50);
        assertEquals(1,history.total(),"stream completion cannot precede shared history persistence");
        assertEquals("已完成回复锚点",history.messages().getFirst().content());
    }
    @Test void queuedAppendCannotResurrectADeletedConversation() {
        first.appendAsync(key,List.of(new ChatConversationMemoryPort.StoredMessage(ChatConversationMemoryPort.Role.USER,"旧消息")));
        new RedisChatHistoryStoreAdapter(redis).deleteSession(user,key);
        writer.runAll();
        assertEquals(0,second.loadHistory(key,50).total());
        assertFalse(new RedisChatHistoryStoreAdapter(redis).ownsSession(user,key));
    }
    @Test void previouslyAdmittedStreamCannotAppendAfterClear() {
        var stream=adapt(Flux.defer(() -> {
            new RedisChatHistoryStoreAdapter(redis).deleteSession(user,key);
            return Flux.just("清空前已受理的旧回复");
        }));
        stream.collectList().block();writer.runAll();
        assertEquals(0,second.loadHistory(key,50).total());
    }
    @Test void oldCompressionCannotRestoreSummaryAfterClearAndNewMessages() {
        var model=mock(LanguageModelPort.class);when(model.completeText(anyList())).thenReturn("旧会话摘要锚点");
        first.add(key,java.util.stream.IntStream.range(0,60).mapToObj(i ->
                (org.springframework.ai.chat.messages.Message)new org.springframework.ai.chat.messages.UserMessage("旧轮次"+i)).toList());
        var coordinator=new ChatHistoryContextCoordinator(first,model,compressor,TaxonomyPort.empty());
        coordinator.boundedContext(key);
        assertFalse(compressor.tasks.isEmpty(),"fixture must schedule real summary work");
        new RedisChatHistoryStoreAdapter(redis).deleteSession(user,key);
        first.add(key,java.util.stream.IntStream.range(0,60).mapToObj(i ->
                (org.springframework.ai.chat.messages.Message)new org.springframework.ai.chat.messages.UserMessage("新轮次"+i)).toList());
        compressor.runAll();
        assertNull(second.loadSummarySnapshot(key).summary(),"old summary must not be admitted by the new history length");
    }
    @Test void emptyLuaConstraintArrayRemainsReadable() {
        first.add(key,List.of(new org.springframework.ai.chat.messages.UserMessage("本轮内容")));
        first.saveSummarySnapshot(key,"合法摘要",1);
        assertEquals("合法摘要",second.loadSummarySnapshot(key).summary());
        assertTrue(second.loadSummarySnapshot(key).criticalConstraints().isEmpty());
    }
    @Test void oldTurnCannotReintroduceSafetyConstraintsAfterClear() {
        String epoch=first.currentWriteEpoch(key);
        new RedisChatHistoryStoreAdapter(redis).deleteSession(user,key);
        assertTrue(first.rememberCriticalConstraints(key,List.of("不能走台阶"),6000,System.currentTimeMillis()*1000L,epoch).isEmpty());
        assertTrue(second.loadSummarySnapshot(key).criticalConstraints().isEmpty());
    }
    private Flux<com.ai.guide.application.chat.dto.ChatStreamEvent> adapt(Flux<String> provider) {
        return new ChatStreamOutputAdapter().adapt(provider,1000,key,"turn","prompt",SentimentService.Sentiment.NEUTRAL,
                IntentService.Intent.CHITCHAT,first,null);
    }
    private static final class QueuedExecutor extends AbstractExecutorService {
        final Deque<Runnable> tasks=new ArrayDeque<>(); private boolean stopped;
        public void execute(Runnable task) { if(stopped)throw new RejectedExecutionException();tasks.addLast(task); }
        void runAll() { while(!tasks.isEmpty())tasks.removeFirst().run(); }
        public void shutdown(){stopped=true;}
        public List<Runnable> shutdownNow(){stopped=true;var remaining=List.copyOf(tasks);tasks.clear();return remaining;}
        public boolean isShutdown(){return stopped;}
        public boolean isTerminated(){return stopped&&tasks.isEmpty();}
        public boolean awaitTermination(long time,TimeUnit unit){return isTerminated();}
    }
}
