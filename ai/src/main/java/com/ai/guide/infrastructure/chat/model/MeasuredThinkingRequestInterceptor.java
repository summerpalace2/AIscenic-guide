package com.ai.guide.infrastructure.chat.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.io.IOException;
import java.net.URI;

/** Explicit opt-in compatibility for suppliers accepting thinking.type on chat completion requests. */
public final class MeasuredThinkingRequestInterceptor implements ClientHttpRequestInterceptor {
    static final String MARKER = "X-Local-Measured-Disable-Thinking";
    private final URI endpoint;
    private final ObjectMapper json = new ObjectMapper();

    public MeasuredThinkingRequestInterceptor(String baseUrl) {
        this(baseUrl, "/v1/chat/completions");
    }

    public MeasuredThinkingRequestInterceptor(String baseUrl, String completionsPath) {
        endpoint = new DefaultUriBuilderFactory(baseUrl).uriString(completionsPath).build();
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null
                || !("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme())))
            throw new IllegalArgumentException("A valid supplier chat endpoint is required");
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        if (!request.getHeaders().containsKey(MARKER)) return execution.execute(request, body);
        var headers = new HttpHeaders();
        headers.putAll(request.getHeaders());
        String marker = headers.getFirst(MARKER);
        headers.remove(MARKER);
        // Never forward the internal marker, including when misconfigured for another endpoint.
        if (!endpoint.equals(request.getURI()) || !"POST".equals(request.getMethod().name()) || !"true".equals(marker))
            throw new IOException("Measured supplier policy does not match the configured endpoint");
        var root = json.readTree(body);
        if (!(root instanceof ObjectNode object) || object.path("stream").asBoolean(false))
            throw new IOException("Measured supplier policy requires a non-streaming JSON completion");
        object.set("thinking", json.createObjectNode().put("type", "disabled"));
        byte[] transformed = json.writeValueAsBytes(object);
        headers.remove(HttpHeaders.TRANSFER_ENCODING);
        headers.setContentLength(transformed.length);
        HttpRequest wrapped = new HttpRequestWrapper(request) {
            @Override public HttpHeaders getHeaders() { return headers; }
        };
        return execution.execute(wrapped, transformed);
    }
}
