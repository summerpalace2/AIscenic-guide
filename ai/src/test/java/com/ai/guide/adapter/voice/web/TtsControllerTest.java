package com.ai.guide.adapter.voice.web;

import com.ai.guide.application.voice.VoiceApplicationService;
import com.ai.guide.domain.voice.model.VoiceSynthesisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("TtsController 适配层测试")
class TtsControllerTest {

    private final VoiceApplicationService applicationService = mock(VoiceApplicationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new TtsController(applicationService)).build();

    @Test
    @DisplayName("POST /ai/tts - 成功合成语音二进制流")
    void testSynthesizeSuccess() throws Exception {
        byte[] mp3Bytes = new byte[]{1, 2, 3, 4};
        when(applicationService.synthesize(anyMap()))
                .thenReturn(VoiceSynthesisResult.success(mp3Bytes));

        mockMvc.perform(post("/ai/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"欢迎来到重庆\",\"voice\":\"106\",\"lang\":\"zh\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=tts.mp3"))
                .andExpect(content().bytes(mp3Bytes));
    }

    @Test
    @DisplayName("POST /ai/tts - 文本为空时返回 400 Bad Request")
    void testSynthesizeEmptyText() throws Exception {
        when(applicationService.synthesize(anyMap()))
                .thenReturn(VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.BAD_REQUEST, "待合成文本不能为空"));

        mockMvc.perform(post("/ai/tts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /ai/tts - 将领域失败分类映射到对应 HTTP 状态")
    void testSynthesizeFailureStatusMapping() throws Exception {
        when(applicationService.synthesize(anyMap()))
                .thenReturn(VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.PAYLOAD_TOO_LARGE, "文本过长"))
                .thenReturn(VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.UPSTREAM_FAILURE, "供应商失败"))
                .thenReturn(VoiceSynthesisResult.failure(VoiceSynthesisResult.Status.UNAVAILABLE, "功能关闭"));

        mockMvc.perform(post("/ai/tts").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"a\"}"))
                .andExpect(status().isPayloadTooLarge());
        mockMvc.perform(post("/ai/tts").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"a\"}"))
                .andExpect(status().isBadGateway());
        mockMvc.perform(post("/ai/tts").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"a\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
