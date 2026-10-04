package com.ai.guide.infrastructure.voice.baidu;

import com.ai.guide.common.model.Result;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("BaiduVoiceClient 基础设施层客户端测试")
class BaiduVoiceClientTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BaiduVoiceClient client = new BaiduVoiceClient(restTemplate, objectMapper);

    @Test
    @DisplayName("handleBaiduError - 百度错误码精准映射为中文提示与 HTTP 状态码")
    void testHandleBaiduError() {
        assertEquals(400, client.handleBaiduError(3001, "").getCode());
        assertEquals("音频质量过差，请重新录制（靠近麦克风、减少噪音）", client.handleBaiduError(3001, "").getMessage());

        assertEquals(401, client.handleBaiduError(3002, "").getCode());
        assertEquals("鉴权失败，百度 API Key 无效", client.handleBaiduError(3002, "").getMessage());

        assertEquals(429, client.handleBaiduError(3003, "").getCode());
        assertEquals(401, client.handleBaiduError(3100, "").getCode());
        assertEquals(400, client.handleBaiduError(3301, "").getCode());
        assertEquals(429, client.handleBaiduError(3303, "").getCode());
        assertEquals(500, client.handleBaiduError(9999, "").getCode());
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

        Result<String> result = client.transcribe(new byte[]{1, 2}, "AQI=", 1537);
        assertEquals(200, result.getCode());
        assertEquals("识别成功", result.getMessage());
        assertEquals("重庆火锅好吃", result.getData());
    }

    @Test
    @DisplayName("transcribe - 远程调用抛出 HttpClientErrorException 时保留真实 HTTP 状态码")
    void testTranscribeHttpError() {
        when(restTemplate.getForEntity(contains("oauth/2.0/token"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "fake-token")));

        when(restTemplate.postForEntity(contains("server_api"), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "Unauthorized"));

        Result<String> result = client.transcribe(new byte[]{1, 2}, "AQI=", 1537);
        assertEquals(401, result.getCode());
        assertTrue(result.getMessage().contains("HTTP 401"));
    }

    @Test
    @DisplayName("synthesize - 成功调用百度 TTS 获取二进制音频并设置头信息")
    void testSynthesizeSuccess() {
        when(restTemplate.getForEntity(contains("oauth/2.0/token"), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", "fake-token")));

        byte[] fakeMp3 = new byte[]{1, 2, 3};
        when(restTemplate.exchange(contains("text2audio"), eq(HttpMethod.GET), isNull(), eq(byte[].class)))
                .thenReturn(ResponseEntity.ok(fakeMp3));

        ResponseEntity<byte[]> response = client.synthesize("解放碑", "106", "zh");
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals(fakeMp3, response.getBody());
        assertEquals("attachment; filename=tts.mp3", response.getHeaders().getFirst("Content-Disposition"));
    }
}
