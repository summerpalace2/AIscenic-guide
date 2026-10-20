package com.ai.guide.infrastructure.user.security;

import com.ai.guide.common.security.JwtUtil;
import com.ai.guide.domain.user.gateway.JwtTokenIssuerPort;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenIssuerAdapter implements JwtTokenIssuerPort {
    private final JwtUtil jwtUtil;
    public JwtTokenIssuerAdapter(JwtUtil jwtUtil) { this.jwtUtil = jwtUtil; }
    @Override public String issue(String userId, String username, String role) {
        return jwtUtil.generateToken(userId, username, role);
    }
}
