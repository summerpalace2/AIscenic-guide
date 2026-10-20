package com.ai.guide.infrastructure.chat.model;

import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MeasuredThinkingHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<LanguageModelMessage> MESSAGES = List.of(
            new LanguageModelMessage(LanguageModelMessage.Role.USER, "Return a short answer"));

    @Test void actualM3HttpBodyDisablesThinkingOnlyForMeasuredCalls() throws Exception {
        verifyRealHttpPipeline("", "/v1/chat/completions");
    }

    @Test void versionedBaseUsesConfiguredRelativeCompletionPathWithoutDuplicatingVersion() throws Exception {
        verifyRealHttpPipeline("/v1", "/chat/completions");
    }

    private void verifyRealHttpPipeline(String basePath, String completionPath) throws Exception {
        var bodies = new CopyOnWriteArrayList<JsonNode>();
        var paths = new CopyOnWriteArrayList<String>();
        var leakedMarkers = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestHeaders().containsKey(MeasuredThinkingRequestInterceptor.MARKER)) leakedMarkers.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(JSON.readTree(exchange.getRequestBody().readAllBytes()));
            byte[] response = """
                    {"id":"test","object":"chat.completion","created":1,"model":"fixture-model",
                     "choices":[{"index":0,"message":{"role":"assistant","content":"answer"},"finish_reason":"stop"}],
                     "usage":{"prompt_tokens":17,"completion_tokens":5,"total_tokens":22}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + basePath;
            var rest = RestClient.builder();
            new MeasuredThinkingConfiguration().measuredThinkingCustomizer(base, completionPath).customize(rest);
            var api = new OpenAiApi(base, "fixture-not-secret", completionPath, "/embeddings",
                    rest, WebClient.builder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
            var model = new OpenAiChatModel(api, OpenAiChatOptions.builder().withModel("fixture-model")
                    .withTemperature(0.3).build());
            var adapter = new SpringAiChatModelAdapter(ChatClient.builder(model), true);
            var measured = adapter.completeMeasured(MESSAGES, 0);
            assertEquals("answer", measured.text());
            assertEquals(22L, measured.usage().totalTokens());
            assertEquals("answer", adapter.completeText(MESSAGES));
            assertEquals(2, bodies.size());
            assertEquals(0.0, bodies.get(0).path("temperature").asDouble());
            assertEquals("disabled", bodies.get(0).path("thinking").path("type").asText());
            assertFalse(bodies.get(1).has("thinking"));
            assertEquals(0.3, bodies.get(1).path("temperature").asDouble());
            assertEquals(0, leakedMarkers.get());
            assertEquals(List.of(basePath + completionPath, basePath + completionPath), paths);
        } finally {
            server.stop(0);
        }
    }

    @Test void markedRequestCannotEscapeToAnotherEndpoint() {
        var policy = new MeasuredThinkingRequestInterceptor("https://supplier.example");
        var request = org.mockito.Mockito.mock(org.springframework.http.HttpRequest.class);
        var headers = new org.springframework.http.HttpHeaders();
        headers.set(MeasuredThinkingRequestInterceptor.MARKER, "true");
        org.mockito.Mockito.when(request.getHeaders()).thenReturn(headers);
        org.mockito.Mockito.when(request.getURI()).thenReturn(java.net.URI.create("https://another.example/v1/chat/completions"));
        var execution = org.mockito.Mockito.mock(org.springframework.http.client.ClientHttpRequestExecution.class);
        assertThrows(java.io.IOException.class, () -> policy.intercept(request, "{}".getBytes(StandardCharsets.UTF_8), execution));
        org.mockito.Mockito.verifyNoInteractions(execution);
    }
}
