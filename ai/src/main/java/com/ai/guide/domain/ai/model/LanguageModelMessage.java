package com.ai.guide.domain.ai.model;

import java.util.Objects;

/** Provider-neutral message exchanged with a language model capability. */
public record LanguageModelMessage(Role role, String content) {
    public LanguageModelMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public enum Role { SYSTEM, USER, ASSISTANT }
}
