package com.ai.guide.domain.user.service;

import com.ai.guide.domain.user.gateway.UserAccountRepositoryPort;
import com.ai.guide.domain.user.model.User;
import com.ai.guide.domain.user.gateway.LegacyPasswordVerifierPort;
import com.ai.guide.domain.user.gateway.PasswordEncoderPort;
import com.ai.guide.domain.user.gateway.JwtTokenIssuerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final UserAccountRepositoryPort accountRepository;
    private final PasswordEncoderPort passwordEncoder;
    private final JwtTokenIssuerPort tokenIssuer;
    private final LegacyPasswordVerifierPort legacyScryptVerifier;

    @Autowired
    public UserService(UserAccountRepositoryPort accountRepository,
                       PasswordEncoderPort passwordEncoder,
                       JwtTokenIssuerPort tokenIssuer,
                       LegacyPasswordVerifierPort legacyScryptVerifier) {
        this.accountRepository = accountRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenIssuer = tokenIssuer;
        this.legacyScryptVerifier = legacyScryptVerifier;
    }

    public Map<String, Object> register(String username, String password) {
        if (accountRepository.usernameExists(username)) throw new IllegalArgumentException("用户名已存在");
        String id = UUID.randomUUID().toString();
        String pwHash = passwordEncoder.encode(password);
        String now = LocalDateTime.now().format(DT_FMT);
        accountRepository.create(id, username, pwHash, now);
        String token = tokenIssuer.issue(id, username, "USER");
        log.info("[Auth] 注册成功: {} -> {}", username, id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", id);
        result.put("username", username);
        result.put("token", token);
        result.put("role", "USER");
        return result;
    }

    public Map<String, Object> login(String username, String password) {
        User u = accountRepository.findByUsername(username).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!"active".equalsIgnoreCase(u.getStatus())) throw new IllegalArgumentException("账号已禁用");
        if (!isSupportedRole(u.getRole())) throw new IllegalArgumentException("账号权限状态无效，请联系管理员");
        if (u.isResetRequired()) throw new IllegalArgumentException("密码需要重置");

        String scheme = u.getPasswordScheme() == null
                ? ""
                : u.getPasswordScheme().trim().toUpperCase(java.util.Locale.ROOT);
        switch (scheme) {
            case "BCRYPT" -> verifyBcryptAndMaybeRehash(u, password);
            case LegacyPasswordVerifierPort.SCHEME -> verifyLegacyAndUpgrade(u, password);
            default -> throw new IllegalArgumentException("密码需要重置");
        }

        String token = tokenIssuer.issue(u.getId(), u.getUsername(), u.getRole());
        log.info("[Auth] 登录成功: {} ({})", username, u.getId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", u.getId());
        result.put("username", u.getUsername());
        result.put("token", token);
        result.put("role", u.getRole());
        return result;
    }

    private void verifyBcryptAndMaybeRehash(User user, String password) {
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("密码错误");
        }
        if (user.isRehashRequired() || passwordEncoder.needsRehash(user.getPasswordHash())) {
            String upgradedHash = passwordEncoder.encode(password);
            accountRepository.updateBcryptCredentials(
                    user.getId(), upgradedHash, LocalDateTime.now().format(DT_FMT));
        }
    }

    private void verifyLegacyAndUpgrade(User user, String password) {
        if (legacyScryptVerifier == null || !legacyScryptVerifier.isVerifiable(
                user.getPasswordScheme(), user.getPasswordVersion(), user.getPasswordParameters(),
                user.getPasswordSalt(), user.getPasswordHash())) {
            throw new IllegalArgumentException("密码需要重置");
        }
        if (!legacyScryptVerifier.matches(
                password, user.getPasswordScheme(), user.getPasswordVersion(), user.getPasswordParameters(),
                user.getPasswordSalt(), user.getPasswordHash())) {
            throw new IllegalArgumentException("密码错误");
        }

        String upgradedHash = passwordEncoder.encode(password);
        String now = LocalDateTime.now().format(DT_FMT);
        int updated = accountRepository.upgradeLegacyCredentials(user, upgradedHash, now);
        if (updated == 1) return;

        // A concurrent request may have completed the same safe transition. It
        // is acceptable to issue a token only when the current BCrypt hash still
        // verifies this password; otherwise fail closed and make the caller retry.
        User current = accountRepository.findById(user.getId()).orElse(null);
        if (current != null
                && "BCRYPT".equalsIgnoreCase(current.getPasswordScheme())
                && !current.isResetRequired()
                && passwordEncoder.matches(password, current.getPasswordHash())) {
            return;
        }
        throw new IllegalArgumentException("密码状态已更新，请重试");
    }

    /** Returns the current active authority record used at every JWT boundary. */
    public AuthenticatedPrincipal findActivePrincipal(String userId) {
        if (userId == null || userId.isBlank()) return null;
        UserAccountRepositoryPort.Principal stored = accountRepository.findPrincipal(userId).orElse(null);
        if (stored == null) return null;
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(stored.id(), stored.username(), stored.role(), stored.status());
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
        User u = accountRepository.findById(userId).orElse(null);
        if (u == null) return null;
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
        Map<String, Object> assetSummary = accountRepository.findAssetSummary(userId);
        m.put("savedTrips", number(assetSummary.get("saved_trips")));
        m.put("memories", number(assetSummary.get("memories")));
        m.put("profileConfigured", number(assetSummary.get("profile_configured")) > 0);
        return m;
    }

    public Map<String, Object> listUsers(int page, int size) {
        UserAccountRepositoryPort.UserPage storedPage = accountRepository.listUsers(size, (page - 1) * size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", storedPage.total());
        result.put("items", storedPage.items());
        return result;
    }

    @org.springframework.transaction.annotation.Transactional
    public void updateUser(String userId, String role, String status) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("用户 ID 不能为空");
        if (role == null && status == null) throw new IllegalArgumentException("至少提供 role 或 status");

        UserAccountRepositoryPort.Authority current = accountRepository.findAuthority(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        String currentRole = current.role();
        String currentStatus = current.status();

        String nextRole = role == null ? currentRole : canonicalRole(role);
        String nextStatus = status == null ? currentStatus : canonicalStatus(status);
        if (!isSupportedRole(currentRole) || !isSupportedStatus(currentStatus)) {
            throw new IllegalArgumentException("当前用户权限状态无效，拒绝修改");
        }
        if (isAdminRole(currentRole)
                && (!isAdminRole(nextRole) || "disabled".equalsIgnoreCase(nextStatus))) {
            int remaining = accountRepository.countOtherActiveAdministrators(userId);
            if (remaining < 1) {
                throw new IllegalArgumentException("不能禁用或降级最后一个有效管理员");
            }
        }

        int updated = accountRepository.updateRoleAndStatus(
                userId, nextRole, nextStatus, LocalDateTime.now().format(DT_FMT));
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
