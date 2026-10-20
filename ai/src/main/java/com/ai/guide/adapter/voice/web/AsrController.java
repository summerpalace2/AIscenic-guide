package com.ai.guide.adapter.voice.web;

import com.ai.guide.application.voice.VoiceApplicationService;
import com.ai.guide.common.model.Result;
import com.ai.guide.domain.voice.model.VoiceTranscriptionResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 语音识别控制器 - 百度短语音识别入口
 *
 * 所属适配层：adapter.voice.web
 * 职责：接收前端语音识别请求，委派语音应用服务进行转写。
 */
@RestController
@RequestMapping("/ai")
public class AsrController {

    private final VoiceApplicationService voiceApplicationService;

    public AsrController(VoiceApplicationService voiceApplicationService) {
        this.voiceApplicationService = voiceApplicationService;
    }

    @PostMapping(value = "/asr", consumes = "application/json")
    public ResponseEntity<Result<String>> transcribe(@RequestBody Map<String, String> body) {
        VoiceTranscriptionResult result = voiceApplicationService.transcribe(body);
        if (result.successful()) {
            return ResponseEntity.ok(Result.success(result.message(), result.transcript()));
        }
        HttpStatus status = switch (result.status()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case PAYLOAD_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case PROVIDER_FAILURE -> HttpStatus.INTERNAL_SERVER_ERROR;
            case SUCCESS -> throw new IllegalStateException("Successful result must contain a transcript");
        };
        return ResponseEntity.status(status).body(Result.error(status.value(), result.message()));
    }
}
