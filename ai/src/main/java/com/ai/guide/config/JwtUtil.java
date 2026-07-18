package com.ai.guide.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类
 * 原始 Python 版本由 sleepearlyplease 创建，Java 转化由 summerpalace2 实现
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret:scenic-guide-default-secret-key-change-in-production}")
    private String secret;

    @Value("${jwt.expires-days:7}")
    private int expiresDays;

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String userId, String username, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim("username", username)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expiresDays * 86400000L))
                .signWith(getSigningKey())
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parser().verifyWith(getSigningKey()).build().parseSignedClaims(token).getPayload();
        } catch (Exception e) {
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
}