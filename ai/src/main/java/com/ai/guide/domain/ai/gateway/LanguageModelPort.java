package com.ai.guide.domain.ai.gateway;

import com.ai.guide.domain.ai.model.LanguageModelMessage;
import com.ai.guide.domain.ai.model.LanguageModelCompletion;
import com.ai.guide.domain.ai.model.LanguageModelUsage;
import org.reactivestreams.Publisher;

import java.util.List;

/** Provider-neutral language generation capability shared by bounded domain use cases. */
public interface LanguageModelPort {
    Publisher<String> stream(List<LanguageModelMessage> messages);
    <T> T complete(List<LanguageModelMessage> messages, Class<T> responseType);
    String completeText(List<LanguageModelMessage> messages);

    /**
     * Compatible fallback for older implementations. They cannot promise per-call temperature
     * control or token measurement; adapters supporting these capabilities override this method.
     */
    default LanguageModelCompletion completeMeasured(List<LanguageModelMessage> messages, double temperature) {
        if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("Temperature must be finite and between 0 and 2");
        }
        long started = System.nanoTime();
        String text = completeText(messages);
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new LanguageModelCompletion(text, LanguageModelUsage.unknown(), null, elapsedMs, false);
    }
}
