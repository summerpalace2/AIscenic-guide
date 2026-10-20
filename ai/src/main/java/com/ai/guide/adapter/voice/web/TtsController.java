package com.ai.guide.adapter.voice.web;

import com.ai.guide.application.voice.VoiceApplicationService;
import com.ai.guide.domain.voice.model.VoiceSynthesisResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
    public ResponseEntity<?> tts(@RequestBody Map<String, String> body) {
        VoiceSynthesisResult result = voiceApplicationService.synthesize(body);
        if (result.successful()) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .header("Content-Disposition", "attachment; filename=tts.mp3")
                    .body(result.audio());
        }
        HttpStatus status = switch (result.status()) {
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case UPSTREAM_FAILURE -> HttpStatus.BAD_GATEWAY;
            case UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case SUCCESS -> throw new IllegalStateException("Successful result must contain audio");
        };
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("code", status.value(), "message", result.message()));
    }
}
