package com.ai.guide.infrastructure.voice.baidu;

import com.ai.guide.domain.voice.gateway.VoiceGatewayPort;
import com.ai.guide.domain.voice.model.VoiceSynthesisResult;
import com.ai.guide.domain.voice.model.VoiceTranscriptionResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.util.LinkedMultiValueMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
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

    @Value("${baidu.api-key:}")
    private String apiKey;

    @Value("${baidu.secret-key:}")
    private String secretKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String asrUrl;
    private final String ttsUrl;
    private final String tokenUrl;

    @Autowired
    public BaiduVoiceClient(@Value("${baidu.connect-timeout-ms:5000}") int connectTimeoutMs,
                            @Value("${baidu.read-timeout-ms:15000}") int readTimeoutMs,
                            @Value("${baidu.voice.asr-url}") String asrUrl,
                            @Value("${baidu.voice.tts-url}") String ttsUrl,
                            @Value("${baidu.voice.token-url}") String tokenUrl) {
        var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();factory.setConnectTimeout(connectTimeoutMs);factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
        this.objectMapper = new ObjectMapper();
        this.asrUrl = requireUrl(asrUrl);
        this.ttsUrl = requireUrl(ttsUrl);
        this.tokenUrl = requireUrl(tokenUrl);
    }

    public BaiduVoiceClient(RestTemplate restTemplate, ObjectMapper objectMapper,
                            String asrUrl, String ttsUrl, String tokenUrl) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.asrUrl = requireUrl(asrUrl);
        this.ttsUrl = requireUrl(ttsUrl);
        this.tokenUrl = requireUrl(tokenUrl);
    }

    @Override
    public VoiceTranscriptionResult transcribe(byte[] audioBytes, String base64Audio, int devPid) {
        try {
            String token = getAccessToken();
            String cuid = UUID.randomUUID().toString().replace("-", "").substring(0, 16);

            String requestBody = String.format(
                    "{\"format\":\"wav\",\"rate\":16000,\"channel\":1,\"token\":\"%s\",\"cuid\":\"%s\",\"dev_pid\":%d,\"len\":%d,\"speech\":\"%s\"}",
                    token, cuid, devPid, audioBytes.length, base64Audio);

            log.info("[ASR] POST " + asrUrl + " | audio_len=" + audioBytes.length + " | dev_pid=" + devPid);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(asrUrl, entity, String.class);
            String responseBody = response.getBody();

            log.info("[ASR] Response status={} bytes={}", response.getStatusCode().value(),
                    responseBody == null ? 0 : responseBody.length());

            if (responseBody == null || responseBody.isEmpty()) {
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.PROVIDER_FAILURE, "语音识别返回空结果");
            }

            JsonNode json = objectMapper.readTree(responseBody);
            int errNo = json.path("err_no").asInt(-1);

            if (errNo != 0) {
                String errMsg = json.path("err_msg").asText("unknown");
                log.warn("[ASR] Provider error code={}", errNo);
                return handleBaiduError(errNo, errMsg);
            }

            JsonNode resultNode = json.path("result");
            if (resultNode.isArray() && resultNode.size() > 0) {
                String text = resultNode.get(0).asText().trim();
                if (!text.isEmpty()) {
                    return VoiceTranscriptionResult.success("识别成功", text);
                }
            }
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.PROVIDER_FAILURE, "未识别到内容，请重新录制");

        } catch (HttpClientErrorException e) {
            log.warn("[ASR] HTTP error status={}", e.getStatusCode().value());
            return VoiceTranscriptionResult.failure(statusForHttp(e.getStatusCode().value()), "语音识别服务异常（HTTP " + e.getStatusCode().value() + "）");
        } catch (Exception e) {
            // HTTP exception messages may contain token URLs or provider response text.
            log.warn("[ASR] Request failed type={}", e.getClass().getSimpleName());
            return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.PROVIDER_FAILURE, "转写失败，请稍后重试");
        }
    }

    @Override
    public VoiceSynthesisResult synthesize(String text, String per, String lang) {
        // Baidu's short-text endpoint limits the original text, not its URL-encoded representation.
        if (text.getBytes(Charset.forName("GBK")).length >= 1024) {
            return VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.PAYLOAD_TOO_LARGE,
                    "朗读文本过长，请选择较短内容（少于1024 GBK字节）");
        }
        try {
            String token = getAccessToken();
            var form = new LinkedMultiValueMap<String, String>();
            // Baidu recommends two URL encodings for tex, including '+' and '%'.
            // The form converter performs the second encoding when writing the request body.
            form.add("tex", URLEncoder.encode(text, StandardCharsets.UTF_8));
            form.add("tok", token);
            form.add("cuid", "scenic_guide_tts");
            form.add("ctp", "1");
            form.add("lan", lang);
            form.add("spd", "5");
            form.add("pit", "5");
            form.add("vol", "5");
            form.add("per", per);
            form.add("aue", "3");
            var headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            log.info("[TTS] lang=" + lang + ", voice=" + per + ", text_len=" + text.length());
            ResponseEntity<byte[]> response = restTemplate.exchange(ttsUrl, HttpMethod.POST,
                    new HttpEntity<>(form, headers), byte[].class);
            byte[] audio=response.getBody();MediaType contentType=response.getHeaders().getContentType();
            boolean jsonType=contentType!=null&&(MediaType.APPLICATION_JSON.isCompatibleWith(contentType)||contentType.getSubtype().endsWith("+json"));
            String prefix=audio==null?"":new String(audio,0,Math.min(audio.length,64),StandardCharsets.UTF_8).stripLeading();
            boolean mp3=audio!=null&&audio.length>=3&&((audio[0]=='I'&&audio[1]=='D'&&audio[2]=='3')||((audio[0]&0xff)==0xff&&(audio[1]&0xe0)==0xe0));
            if(!response.getStatusCode().is2xxSuccessful()||jsonType||prefix.startsWith("{")||!mp3) {
                log.warn("[TTS] provider returned a non-audio or unsuccessful response");
                return VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.UPSTREAM_FAILURE, "语音供应商未返回有效音频");
            }
            return VoiceSynthesisResult.success(audio);
        } catch (Exception e) {
            log.warn("[TTS] Request failed type={}", e.getClass().getSimpleName());
            return VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.UPSTREAM_FAILURE, "语音服务暂不可用");
        }
    }

    /**
     * 获取百度 Access Token
     */
    public String getAccessToken() throws Exception {
        String requestUrl = tokenUrl + "?grant_type=client_credentials&client_id=" + apiKey + "&client_secret=" + secretKey;
        ResponseEntity<Map> resp = restTemplate.getForEntity(requestUrl, Map.class);
        Map<String, Object> b = resp.getBody();
        if (b != null && b.containsKey("access_token")) {
            return (String) b.get("access_token");
        }
        throw new java.io.IOException("获取百度 token 失败");
    }

    /**
     * 百度错误码 → 友好中文提示
     */
    public VoiceTranscriptionResult handleBaiduError(int errNo, String errMsg) {
        switch (errNo) {
            case 3001:
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "音频质量过差，请重新录制（靠近麦克风、减少噪音）");
            case 3002:
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.UNAUTHORIZED, "鉴权失败，百度 API Key 无效");
            case 3003:
            case 3005:
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.RATE_LIMITED, "请求超限，请稍后再试");
            case 3100:
            case 3102:
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.UNAUTHORIZED, "鉴权失败，请检查 API Key 配置");
            case 3101:
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "音频格式不支持，请使用中文普通话录制");
            case 3301:
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "未识别到语音内容（环境太安静或未说话）");
            case 3303:
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.RATE_LIMITED, "请求频率过快，请稍后再试");
            case 3307:
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "音频数据为空，请重新录制");
            case 3312:
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.INVALID_REQUEST, "音频格式参数异常，请重新录制");
            default:
                return VoiceTranscriptionResult.failure(VoiceTranscriptionResult.Status.PROVIDER_FAILURE, "语音识别失败，请稍后重试");
        }
    }

    private static VoiceTranscriptionResult.Status statusForHttp(int statusCode) {
        if (statusCode == 401) return VoiceTranscriptionResult.Status.UNAUTHORIZED;
        if (statusCode == 403) return VoiceTranscriptionResult.Status.FORBIDDEN;
        if (statusCode == 429) return VoiceTranscriptionResult.Status.RATE_LIMITED;
        if (statusCode == 413) return VoiceTranscriptionResult.Status.PAYLOAD_TOO_LARGE;
        if (statusCode == 400 || statusCode == 415) return VoiceTranscriptionResult.Status.INVALID_REQUEST;
        return VoiceTranscriptionResult.Status.PROVIDER_FAILURE;
    }

    private static String requireUrl(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Baidu voice endpoint must be configured");
        return value.trim();
    }
}
