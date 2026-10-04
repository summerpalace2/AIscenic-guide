package com.ai.guide.adapter.voice.web;

import com.ai.guide.application.voice.VoiceApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * TTS 控制器 - 语音合成入口
 *
 * 所属适配层：adapter.voice.web
 * 职责：接收前端语音合成请求，委派语音应用服务生成 MP3 语音二进制流。
 */
@RestController
@RequestMapping("/ai")
public class TtsController {

    private final VoiceApplicationService voiceApplicationService;

    public TtsController(VoiceApplicationService voiceApplicationService) {
        this.voiceApplicationService = voiceApplicationService;
    }

    @PostMapping(value = "/tts", consumes = "application/json")
    public ResponseEntity<byte[]> tts(@RequestBody Map<String, String> body) {
        return voiceApplicationService.synthesize(body);
    }
}
