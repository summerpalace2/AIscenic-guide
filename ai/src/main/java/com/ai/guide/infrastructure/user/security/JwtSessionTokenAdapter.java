package com.ai.guide.infrastructure.user.security;

import com.ai.guide.common.security.JwtRevocationService;
import com.ai.guide.common.security.JwtUtil;
import com.ai.guide.domain.user.gateway.JwtSessionTokenPort;
import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Component;

/** Bridges the application token capability to the existing JWT and revocation providers. */
@Component
public class JwtSessionTokenAdapter implements JwtSessionTokenPort {
    private final JwtUtil jwtUtil;
    private final JwtRevocationService revocations;

    public JwtSessionTokenAdapter(JwtUtil jwtUtil, JwtRevocationService revocations) {
        this.jwtUtil = jwtUtil;
        this.revocations = revocations;
    }

    @Override public boolean isValid(String token) {
        Claims claims = jwtUtil.parse(token);
        return claims != null && !revocations.isRevoked(token, claims);
    }

    @Override public boolean revoke(String token) {
        Claims claims = jwtUtil.parse(token);
        return claims != null && revocations.revoke(token, claims);
    }
}
