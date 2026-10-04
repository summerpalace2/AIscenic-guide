package com.ai.guide.application.voice;

import com.ai.guide.common.model.Result;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * 语音交互应用服务门面 (Application Service)
 *
 * 所属应用层：application.voice
 * 职责：编排语音识别 (ASR) 输入校验与格式预处理、语音合成 (TTS) 音色与语种白名单过滤，并委派底层语音网关完成通信。
 */
public interface VoiceApplicationService {

    /**
     * 语音识别转写
     *
     * @param body 请求体，包含 audio（音频 base64 / dataUrl）与可选 dev_pid
     * @return 识别结果字符串或友好错误提示
     */
    Result<String> transcribe(Map<String, String> body);

    /**
     * 语音合成
     *
     * @param body 请求体，包含 text（待合成文本）、voice（音色编号）、lang（语言代码）
     * @return MP3 二进制流响应
     */
    ResponseEntity<byte[]> synthesize(Map<String, String> body);
}
