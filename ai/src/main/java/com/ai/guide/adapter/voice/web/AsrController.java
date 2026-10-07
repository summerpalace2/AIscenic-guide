package com.ai.guide.adapter.voice.web;

import com.ai.guide.application.voice.VoiceApplicationService;
import com.ai.guide.common.model.Result;
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
    public org.springframework.http.ResponseEntity<Result<String>> transcribe(@RequestBody Map<String, String> body) {
        Result<String> result = voiceApplicationService.transcribe(body);
        return org.springframework.http.ResponseEntity.status(result.getCode()).body(result);
    }
}
