package com.ai.guide.domain.user.service;

import com.ai.guide.domain.chat.service.ChatHistoryService;
import com.ai.guide.common.security.JwtUtil;
import com.ai.guide.domain.user.model.User;
import com.ai.guide.domain.user.security.LegacyNodeScryptVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * 用户账户与身份鉴权服务
 *
 * 所属领域：domain.user（用户与认证域）
 * 架构职责：管理用户注册、登录密码比对与平滑哈希升级（支持旧 Node Scrypt 迁移到标准 BCrypt）、个人资料维护与角色权限管理。
 *
 * 核心方法与职责：
 * 1. register：用户注册，校验用户名唯一性，生成加盐 BCrypt 密码并自动发放 JWT Token
 *    - 参数：username（用户名/邮箱），password（明文密码）
 *    - 返回值：包含用户信息与令牌的 Map
 * 2. login：用户登录鉴权，支持 BCrypt 校验及未升级 Scrypt 密码自动重哈希升级
 *    - 参数：username，password
 *    - 返回值：用户信息与 JWT Token
 * 3. getUserById / updateUser：用户资料查询与权限/状态调整
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    private final JdbcTemplate jdbcTemplate;
    private final JwtUtil jwtUtil;
    private final LegacyNodeScryptVerifier legacyScryptVerifier;

    /** Compatibility constructor retained for focused unit tests. */
    public UserService(JdbcTemplate jdbcTemplate, JwtUtil jwtUtil) {
        this(jdbcTemplate, jwtUtil, new LegacyNodeScryptVerifier());
    }

    @Autowired
    public UserService(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate,
                       JwtUtil jwtUtil,
                       LegacyNodeScryptVerifier legacyScryptVerifier) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtUtil = jwtUtil;
        this.legacyScryptVerifier = legacyScryptVerifier;
    }

    private final RowMapper<User> rowMapper = (rs, rowNum) -> {
        User u = new User();
        u.setId(rs.getString("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setPasswordSalt(rs.getString("password_salt"));
        u.setPasswordScheme(rs.getString("password_scheme"));
        u.setPasswordVersion(rs.getString("password_version"));
        u.setPasswordParameters(rs.getString("password_parameters"));
        u.setRehashRequired(rs.getInt("rehash_required") != 0);
        u.setResetRequired(rs.getInt("reset_required") != 0);
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
            "INSERT INTO users (id, username, password_hash, password_scheme, password_version, " +
                    "password_parameters, rehash_required, reset_required, role, status, created_at, updated_at) " +
                    "VALUES (?, ?, ?, 'BCRYPT', '1', '{\"cost\":10}', 0, 0, 'USER', 'active', ?, ?)",
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
        if (!"active".equalsIgnoreCase(u.getStatus())) throw new IllegalArgumentException("账号已禁用");
        if (!isSupportedRole(u.getRole())) throw new IllegalArgumentException("账号权限状态无效，请联系管理员");
        if (u.isResetRequired()) throw new IllegalArgumentException("密码需要重置");

        String scheme = u.getPasswordScheme() == null
                ? ""
                : u.getPasswordScheme().trim().toUpperCase(java.util.Locale.ROOT);
        switch (scheme) {
            case "BCRYPT" -> verifyBcryptAndMaybeRehash(u, password);
            case LegacyNodeScryptVerifier.SCHEME -> verifyLegacyAndUpgrade(u, password);
            default -> throw new IllegalArgumentException("密码需要重置");
        }

        String token = jwtUtil.generateToken(u.getId(), u.getUsername(), u.getRole());
        log.info("[Auth] 登录成功: {} ({})", username, u.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", u.getId());
        result.put("username", u.getUsername());
        result.put("token", token);
        result.put("role", u.getRole());
        return result;
    }

    private void verifyBcryptAndMaybeRehash(User user, String password) {
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("密码错误");
        }
        if (user.isRehashRequired() || encoder.upgradeEncoding(user.getPasswordHash())) {
            String upgradedHash = encoder.encode(password);
            jdbcTemplate.update("UPDATE users SET password_hash = ?, password_salt = '', password_scheme = 'BCRYPT', " +
                            "password_version = '1', password_parameters = '{\"cost\":10}', " +
                            "rehash_required = 0, reset_required = 0, updated_at = ? WHERE id = ?",
                    upgradedHash, LocalDateTime.now().format(DT_FMT), user.getId());
        }
    }

    private void verifyLegacyAndUpgrade(User user, String password) {
        if (!legacyScryptVerifier.isVerifiable(
                user.getPasswordScheme(), user.getPasswordVersion(), user.getPasswordParameters(),
                user.getPasswordSalt(), user.getPasswordHash())) {
            throw new IllegalArgumentException("密码需要重置");
        }
        if (!legacyScryptVerifier.matches(
                password, user.getPasswordScheme(), user.getPasswordVersion(), user.getPasswordParameters(),
                user.getPasswordSalt(), user.getPasswordHash())) {
            throw new IllegalArgumentException("密码错误");
        }

        String upgradedHash = encoder.encode(password);
        String now = LocalDateTime.now().format(DT_FMT);
        int updated = jdbcTemplate.update(
                "UPDATE users SET password_hash = ?, password_salt = '', password_scheme = 'BCRYPT', " +
                        "password_version = '1', password_parameters = '{\"cost\":10}', " +
                        "rehash_required = 0, reset_required = 0, updated_at = ? " +
                        "WHERE id = ? AND password_scheme = ? AND password_version = ? " +
                        "AND password_hash = ? AND password_salt = ?",
                upgradedHash, now, user.getId(), LegacyNodeScryptVerifier.SCHEME,
                LegacyNodeScryptVerifier.VERSION, user.getPasswordHash(), user.getPasswordSalt());
        if (updated == 1) return;

        // A concurrent request may have completed the same safe transition. It
        // is acceptable to issue a token only when the current BCrypt hash still
        // verifies this password; otherwise fail closed and make the caller retry.
        List<User> current = jdbcTemplate.query("SELECT * FROM users WHERE id = ?", rowMapper, user.getId());
        if (current.size() == 1
                && "BCRYPT".equalsIgnoreCase(current.get(0).getPasswordScheme())
                && !current.get(0).isResetRequired()
                && encoder.matches(password, current.get(0).getPasswordHash())) {
            return;
        }
        throw new IllegalArgumentException("密码状态已更新，请重试");
    }

    /** Returns the current active authority record used at every JWT boundary. */
    public AuthenticatedPrincipal findActivePrincipal(String userId) {
        if (userId == null || userId.isBlank()) return null;
        List<AuthenticatedPrincipal> users = jdbcTemplate.query(
                "SELECT id, username, role, status FROM users WHERE id = ?",
                (rs, rowNum) -> new AuthenticatedPrincipal(
                        rs.getString("id"), rs.getString("username"),
                        rs.getString("role"), rs.getString("status")),
                userId);
        if (users.isEmpty()) return null;
        AuthenticatedPrincipal principal = users.get(0);
        if (!"active".equalsIgnoreCase(principal.status())) return null;
        return isSupportedRole(principal.role()) ? principal : null;
    }

    public static boolean isSupportedRole(String role) {
        return "USER".equalsIgnoreCase(role)
                || "ADMIN".equalsIgnoreCase(role)
                || "SUPER_ADMIN".equalsIgnoreCase(role);
    }

    public static boolean isAdminRole(String role) {
        return "ADMIN".equalsIgnoreCase(role) || "SUPER_ADMIN".equalsIgnoreCase(role);
    }

    public static boolean isSupportedStatus(String status) {
        return "active".equalsIgnoreCase(status) || "disabled".equalsIgnoreCase(status);
    }

    public Map<String, Object> getUserById(String userId) {
        List<User> users = jdbcTemplate.query("SELECT * FROM users WHERE id = ?", rowMapper, userId);
        if (users.isEmpty()) return null;
        User u = users.get(0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", u.getId());
        m.put("username", u.getUsername());
        m.put("email", u.getUsername());
        m.put("role", u.getRole());
        m.put("status", u.getStatus());
        m.put("nickname", u.getNickname());
        m.put("avatar", u.getAvatar());
        m.put("phone", u.getPhone());
        m.put("interests", u.getInterests());
        m.put("createdAt", u.getCreatedAt() != null ? u.getCreatedAt().toString() : "");
        Map<String, Object> assetSummary = jdbcTemplate.queryForMap(
                "SELECT COUNT(DISTINCT t.id) AS saved_trips, " +
                        "COUNT(DISTINCT CASE WHEN tm.status = 'CONFIRMED' THEN tm.id END) AS memories, " +
                        "CASE WHEN up.user_id IS NULL THEN 0 ELSE 1 END AS profile_configured " +
                        "FROM users u " +
                        "LEFT JOIN trip t ON t.owner_id = u.id " +
                        "LEFT JOIN travel_memory tm ON tm.user_id = u.id " +
                        "LEFT JOIN user_preferences up ON up.user_id = u.id " +
                        "WHERE u.id = ? GROUP BY u.id, up.user_id", userId);
        m.put("savedTrips", number(assetSummary.get("saved_trips")));
        m.put("memories", number(assetSummary.get("memories")));
        m.put("profileConfigured", number(assetSummary.get("profile_configured")) > 0);
        return m;
    }

    public Map<String, Object> listUsers(int page, int size) {
        Integer total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        List<Map<String, Object>> items = jdbcTemplate.query(
            "SELECT u.id, u.username, u.role, u.status, u.created_at, " +
                    "COUNT(DISTINCT t.id) AS saved_trips, " +
                    "COUNT(DISTINCT CASE WHEN tm.status = 'CONFIRMED' THEN tm.id END) AS memories, " +
                    "CASE WHEN up.user_id IS NULL THEN 0 ELSE 1 END AS profile_configured " +
                    "FROM users u " +
                    "LEFT JOIN trip t ON t.owner_id = u.id " +
                    "LEFT JOIN travel_memory tm ON tm.user_id = u.id " +
                    "LEFT JOIN user_preferences up ON up.user_id = u.id " +
                    "GROUP BY u.id, u.username, u.role, u.status, u.created_at, up.user_id " +
                    "ORDER BY u.created_at DESC LIMIT ? OFFSET ?",
            (rs, rowNum) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString("id"));
                m.put("username", rs.getString("username"));
                m.put("role", rs.getString("role"));
                m.put("status", rs.getString("status"));
                m.put("createdAt", rs.getString("created_at"));
                m.put("savedTrips", rs.getInt("saved_trips"));
                m.put("memories", rs.getInt("memories"));
                m.put("profileConfigured", rs.getInt("profile_configured") != 0);
                return m;
            }, size, (page - 1) * size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total != null ? total : 0);
        result.put("items", items);
        return result;
    }

    @org.springframework.transaction.annotation.Transactional
    public void updateUser(String userId, String role, String status) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("用户 ID 不能为空");
        if (role == null && status == null) throw new IllegalArgumentException("至少提供 role 或 status");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, role, status FROM users WHERE id = ?", userId);
        if (rows.isEmpty()) throw new IllegalArgumentException("用户不存在");
        Map<String, Object> current = rows.get(0);
        String currentRole = String.valueOf(current.get("role"));
        String currentStatus = String.valueOf(current.get("status"));

        String nextRole = role == null ? currentRole : canonicalRole(role);
        String nextStatus = status == null ? currentStatus : canonicalStatus(status);
        if (!isSupportedRole(currentRole) || !isSupportedStatus(currentStatus)) {
            throw new IllegalArgumentException("当前用户权限状态无效，拒绝修改");
        }
        if (isAdminRole(currentRole)
                && (!isAdminRole(nextRole) || "disabled".equalsIgnoreCase(nextStatus))) {
            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE role IN ('ADMIN','SUPER_ADMIN') " +
                            "AND status = 'active' AND id <> ?", Integer.class, userId);
            if (remaining == null || remaining < 1) {
                throw new IllegalArgumentException("不能禁用或降级最后一个有效管理员");
            }
        }

        int updated = jdbcTemplate.update(
                "UPDATE users SET role = ?, status = ?, updated_at = ? WHERE id = ?",
                nextRole, nextStatus, LocalDateTime.now().format(DT_FMT), userId);
        if (updated != 1) throw new IllegalArgumentException("用户不存在");
        log.info("[Auth] 更新用户: {} role={} status={}", userId, nextRole, nextStatus);
    }

    private String canonicalRole(String role) {
        if (!isSupportedRole(role)) throw new IllegalArgumentException("不支持的用户角色");
        if ("SUPER_ADMIN".equalsIgnoreCase(role)) return "SUPER_ADMIN";
        if ("ADMIN".equalsIgnoreCase(role)) return "ADMIN";
        return "USER";
    }

    private String canonicalStatus(String status) {
        if (!isSupportedStatus(status)) throw new IllegalArgumentException("不支持的用户状态");
        return "active".equalsIgnoreCase(status) ? "active" : "disabled";
    }

    public List<String> getUserSessions(String userId) {
        // 联动 ChatHistoryService - 用户级会话隔离
        return List.of();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    public record AuthenticatedPrincipal(String id, String username, String role, String status) { }
}
