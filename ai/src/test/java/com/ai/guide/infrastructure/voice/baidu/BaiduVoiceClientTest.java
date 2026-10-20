package com.ai.guide.infrastructure.voice.baidu;

import com.ai.guide.domain.voice.model.VoiceTranscriptionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BaiduVoiceClient 基础设施层客户端测试")
class BaiduVoiceClientTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BaiduVoiceClient client = new BaiduVoiceClient(restTemplate, objectMapper,
            "https://vop.baidu.com/server_api", "https://tsn.baidu.com/text2audio",
            "https://aip.baidubce.com/oauth/2.0/token");

    @Test
    @DisplayName("handleBaiduError - 百度错误码精准映射为中文提示与 HTTP 状态码")
    void testHandleBaiduError() {
        assertEquals(VoiceTranscriptionResult.Status.INVALID_REQUEST, client.handleBaiduError(3001, "").status());
        assertEquals("音频质量过差，请重新录制（靠近麦克风、减少噪音）", client.handleBaiduError(3001, "").message());

        assertEquals(VoiceTranscriptionResult.Status.UNAUTHORIZED, client.handleBaiduError(3002, "").status());
        assertEquals("鉴权失败，百度 API Key 无效", client.handleBaiduError(3002, "").message());

        assertEquals(VoiceTranscriptionResult.Status.RATE_LIMITED, client.handleBaiduError(3003, "").status());
        assertEquals(VoiceTranscriptionResult.Status.UNAUTHORIZED, client.handleBaiduError(3100, "").status());
        assertEquals(VoiceTranscriptionResult.Status.INVALID_REQUEST, client.handleBaiduError(3301, "").status());
        assertEquals(VoiceTranscriptionResult.Status.RATE_LIMITED, client.handleBaiduError(3303, "").status());
        assertEquals(VoiceTranscriptionResult.Status.PROVIDER_FAILURE, client.handleBaiduError(9999, "").status());
    }

    @Test
    @DisplayName("transcribe - 成功解析百度返回的语音识别文本")
    void testTranscribeSuccess() {
        // Mock token
        when(restTemplate.getForEntity(contains("oauth/2.0/token"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "fake-token")));

        // Mock ASR API
        String baiduResponse = "{\"err_no\":0,\"err_msg\":\"success.\",\"result\":[\"重庆火锅好吃\"]}";
        when(restTemplate.postForEntity(contains("server_api"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(baiduResponse));

        VoiceTranscriptionResult result = client.transcribe(new byte[]{1, 2}, "AQI=", 1537);
        assertTrue(result.successful());
        assertEquals("识别成功", result.message());
        assertEquals("重庆火锅好吃", result.transcript());
    }

    @Test
    @DisplayName("transcribe - 远程调用抛出 HttpClientErrorException 时保留真实 HTTP 状态码")
    void testTranscribeHttpError() {
        when(restTemplate.getForEntity(contains("oauth/2.0/token"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "fake-token")));

        when(restTemplate.postForEntity(contains("server_api"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "Unauthorized"));

        VoiceTranscriptionResult result = client.transcribe(new byte[]{1, 2}, "AQI=", 1537);
        assertEquals(VoiceTranscriptionResult.Status.UNAUTHORIZED, result.status());
        assertTrue(result.message().contains("HTTP 401"));
    }

    @Test
    void transcribePreservesProviderForbiddenAndPayloadTooLargeCategories() {
        when(restTemplate.getForEntity(contains("oauth/2.0/token"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "fake-token")));
        when(restTemplate.postForEntity(contains("server_api"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN, "Forbidden"))
                .thenThrow(new HttpClientErrorException(HttpStatus.PAYLOAD_TOO_LARGE, "Too Large"));

        assertEquals(VoiceTranscriptionResult.Status.FORBIDDEN,
                client.transcribe(new byte[]{1}, "AQ==", 1537).status());
        assertEquals(VoiceTranscriptionResult.Status.PAYLOAD_TOO_LARGE,
                client.transcribe(new byte[]{1}, "AQ==", 1537).status());
    }

    @Test
    @DisplayName("synthesize - 成功调用百度 TTS 获取二进制音频并设置头信息")
    void testSynthesizeSuccess() {
        when(restTemplate.getForEntity(contains("oauth/2.0/token"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "fake-token")));

        byte[] fakeMp3 = new byte[]{73, 68, 51, 1};
        when(restTemplate.exchange(contains("text2audio"), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(fakeMp3));

        var response = client.synthesize("解放碑", "106", "zh");
        assertTrue(response.successful());
        assertArrayEquals(fakeMp3, response.audio());
    }

    @Test
    void shortTextLimitUsesOriginalGbkBytesBeforeAnySupplierCall() {
        assertEquals(com.ai.guide.domain.voice.model.VoiceSynthesisResult.Status.PAYLOAD_TOO_LARGE,
                client.synthesize("中".repeat(512), "106", "zh").status());
        assertEquals(com.ai.guide.domain.voice.model.VoiceSynthesisResult.Status.PAYLOAD_TOO_LARGE,
                client.synthesize("a".repeat(1024), "106", "zh").status());
        verifyNoInteractions(restTemplate);
        when(restTemplate.getForEntity(contains("oauth/2.0/token"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "fake-token")));
        when(restTemplate.exchange(contains("text2audio"), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(new byte[]{73, 68, 51, 1}));
        assertTrue(client.synthesize("中".repeat(511) + "a", "106", "zh").successful());
    }

    @Test
    void actualPostFormPreservesChinesePlusPercentAndDelimitersAfterTwoDecodings() throws Exception {
        var requestMethod = new AtomicReference<String>();
        var requestUri = new AtomicReference<String>();
        var requestType = new AtomicReference<String>();
        var requestBody = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"local-token\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/tts", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestUri.set(exchange.getRequestURI().toString());
            requestType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = {73, 68, 51, 1};
            exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            var local = new BaiduVoiceClient(1000, 1000, base + "/asr", base + "/tts", base + "/token");
            String original = "中文 1+1=2 &x=3 /? # %20 😀";
            assertTrue(local.synthesize(original, "106", "zh").successful());
            assertEquals("POST", requestMethod.get());
            assertEquals("/tts", requestUri.get());
            assertTrue(requestType.get().startsWith("application/x-www-form-urlencoded"));
            var rawFields = new java.util.HashMap<String, String>();
            for (String field : requestBody.get().split("&")) {
                String[] pair = field.split("=", 2);
                rawFields.put(pair[0], pair.length == 2 ? pair[1] : "");
            }
            String once = URLDecoder.decode(rawFields.get("tex"), StandardCharsets.UTF_8);
            assertEquals(original, URLDecoder.decode(once, StandardCharsets.UTF_8));
            assertEquals("local-token", URLDecoder.decode(rawFields.get("tok"), StandardCharsets.UTF_8));
            assertEquals("3", rawFields.get("aue"));
        } finally {
            server.stop(0);
        }
    }
    @Test
    void jsonErrorAndEmptyBodyAreNeverReturnedAsMp3() {
        when(restTemplate.getForEntity(contains("oauth/2.0/token"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "fake-token")));
        when(restTemplate.exchange(contains("text2audio"), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok("{\"err_no\":500,\"err_msg\":\"supplier_secret\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var response=client.synthesize("hello","106","zh");
        assertEquals(com.ai.guide.domain.voice.model.VoiceSynthesisResult.Status.UPSTREAM_FAILURE,response.status());
        assertFalse(response.message().contains("supplier_secret"));
        when(restTemplate.exchange(contains("text2audio"), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(new byte[0]));
        assertEquals(com.ai.guide.domain.voice.model.VoiceSynthesisResult.Status.UPSTREAM_FAILURE,client.synthesize("hello","106","zh").status());
    }

    @Test
    void configuredEndpointsAreUsedForTokenAsrAndTtsCalls() {
        var configured = new BaiduVoiceClient(restTemplate, objectMapper,
                "https://voice.example.test/asr", "https://voice.example.test/tts",
                "https://auth.example.test/token");
        org.springframework.test.util.ReflectionTestUtils.setField(configured, "apiKey", "client");
        org.springframework.test.util.ReflectionTestUtils.setField(configured, "secretKey", "secret");
        when(restTemplate.getForEntity(eq("https://auth.example.test/token?grant_type=client_credentials&client_id=client&client_secret=secret"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "token")));
        when(restTemplate.postForEntity(eq("https://voice.example.test/asr"), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"err_no\":0,\"err_msg\":\"success\",\"result\":[\"ok\"]}"));
        when(restTemplate.exchange(eq("https://voice.example.test/tts"), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(new byte[]{73, 68, 51, 1}));

        assertEquals("ok", configured.transcribe(new byte[]{1}, "AQ==", 1537).transcript());
        assertTrue(configured.synthesize("hello", "106", "zh").successful());
        verify(restTemplate, times(2)).getForEntity(startsWith("https://auth.example.test/token?"), eq(Map.class));
        verify(restTemplate).postForEntity(eq("https://voice.example.test/asr"), any(HttpEntity.class), eq(String.class));
        verify(restTemplate).exchange(eq("https://voice.example.test/tts"), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class));
    }

    @Test
    void voiceLogsNeverContainTranscriptOrSupplierExceptionPayload() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BaiduVoiceClient.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(restTemplate.getForEntity(contains("oauth/2.0/token"), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("access_token", "fake-token")));
            when(restTemplate.postForEntity(contains("server_api"), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"err_no\":0,\"result\":[\"private-transcript-marker\"]}"))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null,
                            "private-provider-body-marker".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("private-transcript-marker", client.transcribe(new byte[]{1}, "AQ==", 1537).transcript());
            client.transcribe(new byte[]{1}, "AQ==", 1537);
            when(restTemplate.exchange(contains("text2audio"), eq(HttpMethod.POST), any(HttpEntity.class), eq(byte[].class)))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("?tok=private-token-marker&tex=private-text-marker"));
            assertFalse(client.synthesize("private-text-marker", "106", "zh").successful());
            String logs = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(logs.contains("private-"), logs);
            assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
