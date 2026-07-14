package com.ai.guide.controller;

import com.ai.guide.model.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * 语音识别控制器 - 百度短语音识别 (http://vop.baidu.com/server_api)
 *
 * 鉴权复用百度 TTS 同一套 API_KEY / SECRET_KEY
 * 前端 encodeWAV 后发送 WAV 格式 → 百度 ASR
 * dev_pid 1537=中文普通话 / 1737=英语 / 1637=粤语 / 1837=四川话
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class AsrController {

    private static final String ASR_URL = "http://vop.baidu.com/server_api";
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    private static final String API_KEY = "vRjlpNUtBFVrWOIssCim9Gf7";
    private static final String SECRET_KEY = "x2UHJKifgL1zxfK2P3m0eiMniAJXyGd9";

    /** 1537 = 中文普通话 */
    private static final int DEV_PID = 1537;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping(value = "/asr", consumes = "application/json")
    public Result<String> transcribe(@RequestBody Map<String, String> body) {
        try {
            String audioDataUrl = body.getOrDefault("audio", "");
            if (audioDataUrl.isEmpty()) {
                return Result.error(400, "音频数据为空");
            }

            // 提取纯 base64（去掉 "data:audio/wav;base64," 前缀）
            String base64Audio = audioDataUrl;
            int commaIdx = audioDataUrl.indexOf(",");
            if (commaIdx != -1) {
                base64Audio = audioDataUrl.substring(commaIdx + 1);
            }

            // 获取百度 Access Token
            String token = getAccessToken();

            // 构造请求体 — format=wav（前端 encodeWAV 生成）
            byte[] audioBytes = java.util.Base64.getDecoder().decode(base64Audio);
            String cuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            String requestBody = String.format(
                    "{\"format\":\"wav\",\"rate\":16000,\"channel\":1,\"token\":\"%s\",\"cuid\":\"%s\",\"dev_pid\":%d,\"len\":%d,\"speech\":\"%s\"}",
                    token, cuid, DEV_PID, audioBytes.length, base64Audio);

            System.out.println("[ASR] POST " + ASR_URL + " | audio_len=" + audioBytes.length + " | dev_pid=" + DEV_PID);

            // POST
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(ASR_URL, entity, String.class);
            String responseBody = response.getBody();

            System.out.println("[ASR] Response: " + (responseBody != null ? responseBody.substring(0, Math.min(500, responseBody.length())) : "null"));

            // 解析
            if (responseBody == null || responseBody.isEmpty()) {
                return Result.error(500, "语音识别返回空结果");
            }

            JsonNode json = objectMapper.readTree(responseBody);
            int errNo = json.path("err_no").asInt(-1);

            if (errNo != 0) {
                String errMsg = json.path("err_msg").asText("unknown");
                System.err.println("[ASR] Baidu error: " + errNo + " - " + errMsg);
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
            System.err.println("[ASR] HTTP Error: " + e.getStatusCode() + " " + e.getResponseBodyAsString());
            return Result.error(e.getStatusCode().value(), "语音识别服务异常（HTTP " + e.getStatusCode().value() + "）");
        } catch (Exception e) {
            System.err.println("[ASR] Error: " + e.getMessage());
            return Result.error(500, "转写失败: " + e.getMessage());
        }
    }

    /**
     * 获取百度 Access Token（与 TTS 共用同一套 API Key）
     */
    private String getAccessToken() throws Exception {
        String tokenUrl = TOKEN_URL + "?grant_type=client_credentials&client_id=" + API_KEY + "&client_secret=" + SECRET_KEY;
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
    private Result<String> handleBaiduError(int errNo, String errMsg) {
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
                return Result.error(500, "语音识别失败（错误码 " + errNo + "：" + errMsg + "）");
        }
    }
}