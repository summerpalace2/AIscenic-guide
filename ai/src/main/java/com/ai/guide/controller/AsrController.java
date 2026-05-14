package com.ai.guide.controller;

import com.ai.guide.model.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音识别接口 —— 阿里 DashScope 多模态音频理解
 */
@CrossOrigin(origins = "*")
@RestController
@RequestMapping("/ai")
public class AsrController {

    @Value("${DASHSCOPE_API_KEY}")
    private String apiKey;

    private static final String MM_URL = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private final RestTemplate restTemplate = new RestTemplate();

    @PostMapping("/asr")
    public Result<String> transcribe(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return Result.error(400, "音频文件为空");

        try {
            byte[] audioBytes = file.getBytes();
            String base64 = java.util.Base64.getEncoder().encodeToString(audioBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            String jsonBody = String.format("""
                {"model":"qwen-audio-turbo-latest","input":{"messages":[
                  {"role":"user","content":[
                    {"audio":"data:audio/webm;base64,%s"},
                    {"text":"请将这段音频转写成中文文字，只输出转写结果，不要加任何额外说明"}
                  ]}
                ]}}""", base64);

            HttpEntity<String> entity = new HttpEntity<>(jsonBody, headers);
            ResponseEntity<String> resp = restTemplate.postForEntity(MM_URL, entity, String.class);
            String body = resp.getBody();
            System.out.println("[ASR] 原始响应(前500): " + (body != null ? body.substring(0, Math.min(500, body.length())) : "null"));
            String result = extractContent(body);
            return result.isEmpty() ? Result.error(500, "未识别到内容") : Result.success("识别成功", result);

        } catch (Exception e) {
            System.err.println("[ASR] 失败: " + e.getMessage());
            return Result.error(500, "语音识别失败: " + e.getMessage());
        }
    }

    private String extractContent(String json) {
        try {
            // 多模态响应: content 是数组 [{"text":"..."}]
            int arrStart = json.indexOf("\"content\":[{\"text\":\"");
            if (arrStart != -1) {
                int textStart = arrStart + 20; // 跳过 "content":[{"text":"
                int textEnd = json.indexOf("\"", textStart);
                if (textEnd != -1) return json.substring(textStart, textEnd);
            }
            // 兼容纯字符串格式: "content":"..."
            int strStart = json.indexOf("\"content\":\"");
            if (strStart != -1) {
                strStart += 11;
                int strEnd = json.indexOf("\"", strStart);
                if (strEnd != -1) return json.substring(strStart, strEnd);
            }
            return "";
        } catch (Exception e) {
            return "";
        }
    }
}
