package com.ai.guide.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具类
 * 原始 Python 版本由 sleepearlyplease 创建，Java 转化由 summerpalace2 实现
 */
@Component
public class JwtUtil {

    private final String secret;
    private final int expiresDays;
    private final SecretKey signingKey;

    public JwtUtil(@Value("${jwt.secret:}") String secret,
                   @Value("${jwt.expires-days:7}") int expiresDays) {
        this.secret = secret == null ? "" : secret.trim();
        if (this.secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET 必须通过受保护配置提供且至少包含 32 个字符。");
        }
        this.expiresDays = Math.max(1, expiresDays);
        this.signingKey = Keys.hmacShaKeyFor(this.secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String userId, String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userId)
                .claim("username", username)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expiresDays * 86400000L))
                .signWith(signingKey)
                .compact();
    }

    /** Returns claims only when signature, structure and time claims are valid. */
    public Claims parse(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
        } catch (Exception ignored) {
            return null;
        }
    }

    public String getUserId(String token) {
        Claims c = parse(token);
        return c != null ? c.getSubject() : null;
    }

    public String getRole(String token) {
        Claims c = parse(token);
        return c != null ? c.get("role", String.class) : null;
    }

    public String getUsername(String token) {
        Claims c = parse(token);
        return c != null ? c.get("username", String.class) : null;
    }
}
