package com.ai.guide.domain.analytics.service;

import com.ai.guide.domain.ai.gateway.LanguageModelPort;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmotionAnalysisLanguageModelPortTest {
    @Test
    void classifiesThroughProviderNeutralLanguageModelCapability() throws Exception {
        LanguageModelPort model = mock(LanguageModelPort.class);
        when(model.completeText(anyList())).thenReturn("NEGATIVE");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var service = new EmotionAnalysisService(model, executor, new SentimentService(), true, 1000);
            assertEquals(SentimentService.Sentiment.NEGATIVE,
                    service.classifyAsync("这次体验很差").get(2, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
