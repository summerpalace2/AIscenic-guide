package com.ai.guide.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * TTS 控制器 - 调用百度合成语音
 *
 * 前端传入百度音色编号（per），后端传递给百度 API。
 * 音色选项：106=度博文(男)、0=度小美(女)、1=度小宇(男)、3=度逍遥(男)、4=度丫丫(女)
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class TtsController {

    private static final String TTS_URL = "https://tsn.baidu.com/text2audio";
    private static final String TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token";
    @Value("${baidu.api-key:}")
    private String apiKey;

    @Value("${baidu.secret-key:}")
    private String secretKey;

    /** 默认音色：度博文（专业讲解风） */
    private static final String DEFAULT_VOICE_PER = "106";

    /** 可用的音色编号白名单 */
    private static final java.util.Set<String> VALID_PER = new java.util.HashSet<>(
            java.util.Arrays.asList("0", "1", "3", "4", "5", "103", "106", "110", "111"));

    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping(value = "/tts", consumes = "application/json")
    public ResponseEntity<byte[]> tts(@RequestBody Map<String, String> body) {
        try {
            String text = body.getOrDefault("text", "");
            if (text.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            // 解析音色参数：前端传入百度 per 编号，白名单校验，非法值用默认
            String per = body.getOrDefault("voice", DEFAULT_VOICE_PER);
            if (!VALID_PER.contains(per)) {
                per = DEFAULT_VOICE_PER;
            }

            String token = getAccessToken();
            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8.name());
            String url = TTS_URL
                    + "?tex=" + encodedText
                    + "&tok=" + token
                    + "&cuid=scenic_guide_tts"
                    + "&ctp=1"
                    + "&lan=zh"
                    + "&spd=5"
                    + "&pit=5"
                    + "&vol=5"
                    + "&per=" + per
                    + "&aue=3";
            System.out.println("[TTS] voice=" + per + ", text_len=" + text.length());
            ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.GET, null, byte[].class);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.set("Content-Disposition", "attachment; filename=tts.mp3");
            return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
        } catch (Exception e) {
            System.err.println("[TTS] Error: " + e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    private String getAccessToken() throws Exception {
        String tokenUrl = TOKEN_URL + "?grant_type=client_credentials&client_id=" + apiKey + "&client_secret=" + secretKey;
        ResponseEntity<Map> resp = restTemplate.getForEntity(tokenUrl, Map.class);
        Map<String, Object> b = resp.getBody();
        if (b != null && b.containsKey("access_token")) return (String) b.get("access_token");
        throw new java.io.IOException("获取百度 token 失败");
    }
}