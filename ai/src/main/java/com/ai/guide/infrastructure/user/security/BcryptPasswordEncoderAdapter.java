package com.ai.guide.infrastructure.user.security;

import com.ai.guide.domain.user.gateway.PasswordEncoderPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BcryptPasswordEncoderAdapter implements PasswordEncoderPort {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    @Override public String encode(String rawPassword) { return encoder.encode(rawPassword); }
    @Override public boolean matches(String rawPassword, String encodedPassword) { return encoder.matches(rawPassword, encodedPassword); }
    @Override public boolean needsRehash(String encodedPassword) { return encoder.upgradeEncoding(encodedPassword); }
}
