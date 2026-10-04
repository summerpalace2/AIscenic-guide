package com.ai.guide.adapter.voice.web;

import com.ai.guide.application.voice.VoiceApplicationService;
import com.ai.guide.common.model.Result;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("AsrController 适配层测试")
class AsrControllerTest {

    private final VoiceApplicationService applicationService = mock(VoiceApplicationService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AsrController(applicationService)).build();

    @Test
    @DisplayName("POST /ai/asr - 成功识别语音")
    void testTranscribeSuccess() throws Exception {
        when(applicationService.transcribe(anyMap()))
                .thenReturn(Result.success("识别成功", "我想去洪崖洞"));

        mockMvc.perform(post("/ai/asr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audio\":\"data:audio/wav;base64,UklGRg==\",\"dev_pid\":\"1537\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("识别成功"))
                .andExpect(jsonPath("$.data").value("我想去洪崖洞"));
    }

    @Test
    @DisplayName("POST /ai/asr - 音频为空时返回 400 校验错误")
    void testTranscribeEmptyAudio() throws Exception {
        when(applicationService.transcribe(anyMap()))
                .thenReturn(Result.error(400, "音频数据为空"));

        mockMvc.perform(post("/ai/asr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audio\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("音频数据为空"));
    }
}
