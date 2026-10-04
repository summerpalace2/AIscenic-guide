package com.ai.guide.domain.voice.gateway;

import com.ai.guide.common.model.Result;
import org.springframework.http.ResponseEntity;

/**
 * 语音服务领域网关端口 (Gateway Port)
 *
 * 所属领域：domain.voice.gateway
 * 职责：定义与底层第三方语音引擎（如百度 ASR / TTS）交互的契约，实现技术实现与领域模型的依赖倒置 (DIP)。
 */
public interface VoiceGatewayPort {

    /**
     * 语音识别转写 (ASR)
     *
     * @param audioBytes  音频原始字节数据
     * @param base64Audio 音频 Base64 编码字符串
     * @param devPid      语种识别代码 (如 1537 普通话)
     * @return 转写文本或错误结果
     */
    Result<String> transcribe(byte[] audioBytes, String base64Audio, int devPid);

    /**
     * 语音合成 (TTS)
     *
     * @param text 待合成文本
     * @param per  音色编号
     * @param lang 语言代码 (zh/en)
     * @return 音频二进制响应实体
     */
    ResponseEntity<byte[]> synthesize(String text, String per, String lang);
}
