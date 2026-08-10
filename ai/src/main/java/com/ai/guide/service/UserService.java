package com.ai.guide.service;

import com.ai.guide.config.JwtUtil;
import com.ai.guide.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户服务 (注册/登录/CRUD)
 * 原始 Python 由 sleepearlyplease 创建，Java 转化由 summerpalace2 实现
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;

    public UserService(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate, JwtUtil jwtUtil) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
    }

    private final RowMapper<User> rowMapper = (rs, rowNum) -> {
        User u = new User();
        u.setId(rs.getString("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setRole(rs.getString("role"));
        u.setStatus(rs.getString("status"));
        u.setNickname(rs.getString("nickname"));
        u.setAvatar(rs.getString("avatar"));
        u.setPhone(rs.getString("phone"));
        u.setInterests(rs.getString("interests"));
        String ca = rs.getString("created_at");
        if (ca != null) u.setCreatedAt(LocalDateTime.parse(ca, DT_FMT));
        String ua = rs.getString("updated_at");
        if (ua != null) u.setUpdatedAt(LocalDateTime.parse(ua, DT_FMT));
        return u;
    };

    public Map<String, Object> register(String username, String password) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        if (count != null && count > 0) throw new IllegalArgumentException("用户名已存在");
        String id = UUID.randomUUID().toString();
        String pwHash = encoder.encode(password);
        String now = LocalDateTime.now().format(DT_FMT);
        jdbcTemplate.update(
            "INSERT INTO users (id, username, password_hash, role, status, created_at, updated_at) VALUES (?, ?, ?, 'USER', 'active', ?, ?)",
            id, username, pwHash, now, now);
        String token = jwtUtil.generateToken(id, username, "USER");
        log.info("[Auth] 注册成功: {} -> {}", username, id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", id);
        result.put("username", username);
        result.put("token", token);
        result.put("role", "USER");
        return result;
    }

    public Map<String, Object> login(String username, String password) {
        List<User> users = jdbcTemplate.query("SELECT * FROM users WHERE username = ?", rowMapper, username);
        if (users.isEmpty()) throw new IllegalArgumentException("用户不存在");
        User u = users.get(0);
        if (!"active".equals(u.getStatus())) throw new IllegalArgumentException("账号已禁用");
        if (!encoder.matches(password, u.getPasswordHash())) throw new IllegalArgumentException("密码错误");
        String token = jwtUtil.generateToken(u.getId(), u.getUsername(), u.getRole());
        log.info("[Auth] 登录成功: {} ({})", username, u.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", u.getId());
        result.put("username", u.getUsername());
        result.put("token", token);
        result.put("role", u.getRole());
        return result;
    }

    public Map<String, Object> getUserById(String userId) {
        List<User> users = jdbcTemplate.query("SELECT * FROM users WHERE id = ?", rowMapper, userId);
        if (users.isEmpty()) return null;
        User u = users.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", u.getId());
        m.put("username", u.getUsername());
        m.put("role", u.getRole());
        m.put("nickname", u.getNickname());
        m.put("avatar", u.getAvatar());
        m.put("phone", u.getPhone());
        m.put("interests", u.getInterests());
        m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
        return m;
    }

    public Map<String, Object> listUsers(int page, int size) {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        List<Map<String, Object>> items = jdbcTemplate.query(
            "SELECT id, username, role, status, created_at FROM users ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("username", rs.getString("username"));
                m.put("role", rs.getString("role"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getString("created_at"));
                return m;
            }, size, (page - 1) * size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total != null ? total : 0);
        result.put("items", items);
        return result;
    }

    public void updateUser(String userId, String role, String status) {
        if (role != null) jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", role, userId);
        if (status != null) jdbcTemplate.update("UPDATE users SET status = ? WHERE id = ?", status, userId);
        log.info("[Auth] 更新用户: {} role={} status={}", userId, role, status);
    }

    public List<String> getUserSessions(String userId) {
        // 联动 ChatHistoryService - 用户级会话隔离
        return List.of();
    }
}