package com.ai.guide.infrastructure.chat.model;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Activated only by the configured supplier policy; other suppliers must disable this extension. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "rag.semantic.disable-thinking", havingValue = "true")
public class MeasuredThinkingConfiguration {
    @Bean
    RestClientCustomizer measuredThinkingCustomizer(
            @Value("${spring.ai.openai.chat.base-url:${spring.ai.openai.base-url:https://api.openai.com}}") String baseUrl,
            @Value("${spring.ai.openai.chat.completions-path:/v1/chat/completions}") String completionsPath) {
        return builder -> builder.requestInterceptor(new MeasuredThinkingRequestInterceptor(baseUrl, completionsPath));
    }
}
