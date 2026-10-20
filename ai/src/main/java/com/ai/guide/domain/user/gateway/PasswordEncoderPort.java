package com.ai.guide.domain.user.gateway;

/** Password hashing capability required by account workflows. */
public interface PasswordEncoderPort {
    String encode(String rawPassword);
    boolean matches(String rawPassword, String encodedPassword);
    boolean needsRehash(String encodedPassword);
}
