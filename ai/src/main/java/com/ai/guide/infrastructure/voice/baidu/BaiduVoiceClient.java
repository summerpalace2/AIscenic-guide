package com.ai.guide.infrastructure.voice.baidu;

import com.ai.guide.common.model.Result;
import com.ai.guide.domain.voice.gateway.VoiceGatewayPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 百度语音技术客户端 (ASR 语音识别 & TTS 语音合成)
 *
 * 所属基础设施层：infrastructure.voice.baidu
 * 职责：封装百度云智能开放平台 ASR 与 TTS 的 HTTP 通信细节、OAuth2 Token 鉴权以及错误码转译。
 */
@Component
public class BaiduVoiceClient implements VoiceGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(BaiduVoiceClient.class);

    private static final String ASR_URL = "http://vop.baidu.com/server_api";
    private static final String TTS_URL = "https://tsn.baidu.com/text2audio";
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";

    @Value("${baidu.api-key:}")
    private String apiKey;

    @Value("${baidu.secret-key:}")
    private String secretKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public BaiduVoiceClient() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
    }

    public BaiduVoiceClient(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Result<String> transcribe(byte[] audioBytes, String base64Audio, int devPid) {
        try {
            String token = getAccessToken();
            String cuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            String requestBody = String.format(
                    "{\"format\":\"wav\",\"rate\":16000,\"channel\":1,\"token\":\"%s\",\"cuid\":\"%s\",\"dev_pid\":%d,\"len\":%d,\"speech\":\"%s\"}",
                    token, cuid, devPid, audioBytes.length, base64Audio);

            log.info("[ASR] POST " + ASR_URL + " | audio_len=" + audioBytes.length + " | dev_pid=" + devPid);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(ASR_URL, entity, String.class);
            String responseBody = response.getBody();

            log.info("[ASR] Response: " + (responseBody != null ? responseBody.substring(0, Math.min(500, responseBody.length())) : "null"));

            if (responseBody == null || responseBody.isEmpty()) {
                return Result.error(500, "语音识别返回空结果");
            }

            JsonNode json = objectMapper.readTree(responseBody);
            int errNo = json.path("err_no").asInt(-1);

            if (errNo != 0) {
                String errMsg = json.path("err_msg").asText("unknown");
                log.error("[ASR] Baidu error: " + errNo + " - " + errMsg);
                return handleBaiduError(errNo, errMsg);
            }

            JsonNode resultNode = json.path("result");
            if (resultNode.isArray() && resultNode.size() > 0) {
                String text = resultNode.get(0).asText().trim();
                if (!text.isEmpty()) {
                    return Result.success("识别成功", text);
                }
            }
            return Result.error(500, "未识别到内容，请重新录制");

        } catch (HttpClientErrorException e) {
            log.error("[ASR] HTTP Error: " + e.getStatusCode() + " " + e.getResponseBodyAsString());
            return Result.error(e.getStatusCode().value(), "语音识别服务异常（HTTP " + e.getStatusCode().value() + "）");
        } catch (Exception e) {
            log.error("[ASR] Error: " + e.getMessage());
            return Result.error(500, "转写失败，请稍后重试");
        }
    }

    @Override
    public ResponseEntity<byte[]> synthesize(String text, String per, String lang) {
        try {
            String token = getAccessToken();
            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
            String url = TTS_URL
                    + "?tex=" + encodedText
                    + "&tok=" + token
                    + "&cuid=scenic_guide_tts"
                    + "&ctp=1"
                    + "&lan=" + lang
                    + "&spd=5"
                    + "&pit=5"
                    + "&vol=5"
                    + "&per=" + per
                    + "&aue=3";

            log.info("[TTS] lang=" + lang + ", voice=" + per + ", text_len=" + text.length());
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, null, byte[].class);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.set("Content-Disposition", "attachment; filename=tts.mp3");
            return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
        } catch (Exception e) {
            log.error("[TTS] Error: " + e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取百度 Access Token
     */
    public String getAccessToken() throws Exception {
        String tokenUrl = TOKEN_URL + "?grant_type=client_credentials&client_id=" + apiKey + "&client_secret=" + secretKey;
        ResponseEntity<Map> resp = restTemplate.getForEntity(tokenUrl, Map.class);
        Map<String, Object> b = resp.getBody();
        if (b != null && b.containsKey("access_token")) {
            return (String) b.get("access_token");
        }
        throw new java.io.IOException("获取百度 token 失败");
    }

    /**
     * 百度错误码 → 友好中文提示
     */
    public Result<String> handleBaiduError(int errNo, String errMsg) {
        switch (errNo) {
            case 3001:
                return Result.error(400, "音频质量过差，请重新录制（靠近麦克风、减少噪音）");
            case 3002:
                return Result.error(401, "鉴权失败，百度 API Key 无效");
            case 3003:
            case 3005:
                return Result.error(429, "请求超限，请稍后再试");
            case 3100:
            case 3102:
                return Result.error(401, "鉴权失败，请检查 API Key 配置");
            case 3101:
                return Result.error(400, "音频格式不支持，请使用中文普通话录制");
            case 3301:
                return Result.error(400, "未识别到语音内容（环境太安静或未说话）");
            case 3303:
                return Result.error(429, "请求频率过快，请稍后再试");
            case 3307:
                return Result.error(400, "音频数据为空，请重新录制");
            case 3312:
                return Result.error(400, "音频格式参数异常，请重新录制");
            default:
                return Result.error(500, "语音识别失败，请稍后重试");
        }
    }
}
