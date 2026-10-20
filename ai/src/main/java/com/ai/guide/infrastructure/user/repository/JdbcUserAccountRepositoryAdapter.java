package com.ai.guide.infrastructure.user.repository;

import com.ai.guide.domain.user.gateway.UserAccountRepositoryPort;
import com.ai.guide.domain.user.model.User;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC account repository implementation. */
@Repository
public class JdbcUserAccountRepositoryAdapter implements UserAccountRepositoryPort {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<User> userMapper = (rs, rowNum) -> {
        User user = new User();
        user.setId(rs.getString("id"));
        user.setUsername(rs.getString("username"));
        user.setPasswordHash(rs.getString("password_hash"));
        user.setPasswordSalt(rs.getString("password_salt"));
        user.setPasswordScheme(rs.getString("password_scheme"));
        user.setPasswordVersion(rs.getString("password_version"));
        user.setPasswordParameters(rs.getString("password_parameters"));
        user.setRehashRequired(rs.getInt("rehash_required") != 0);
        user.setResetRequired(rs.getInt("reset_required") != 0);
        user.setRole(rs.getString("role"));
        user.setStatus(rs.getString("status"));
        user.setNickname(rs.getString("nickname"));
        user.setAvatar(rs.getString("avatar"));
        user.setPhone(rs.getString("phone"));
        user.setInterests(rs.getString("interests"));
        String created = rs.getString("created_at");
        if (created != null) user.setCreatedAt(LocalDateTime.parse(created, DATE_FORMAT));
        String updated = rs.getString("updated_at");
        if (updated != null) user.setUpdatedAt(LocalDateTime.parse(updated, DATE_FORMAT));
        return user;
    };

    public JdbcUserAccountRepositoryAdapter(@Qualifier("knowledgeJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean usernameExists(String username) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    @Override
    public int countAdministrators() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE role IN ('ADMIN','SUPER_ADMIN')", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void createAdministrator(String id, String username, String passwordHash, String createdAt) {
        jdbcTemplate.update("INSERT INTO users (id, username, password_hash, password_scheme, password_version, " +
                        "password_parameters, rehash_required, reset_required, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'BCRYPT', '1', '{\"cost\":10}', 0, 0, 'ADMIN', 'active', ?, ?)",
                id, username, passwordHash, createdAt, createdAt);
    }

    @Override
    public void create(String id, String username, String passwordHash, String createdAt) {
        jdbcTemplate.update(
                "INSERT INTO users (id, username, password_hash, password_scheme, password_version, " +
                        "password_parameters, rehash_required, reset_required, role, status, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'BCRYPT', '1', '{\"cost\":10}', 0, 0, 'USER', 'active', ?, ?)",
                id, username, passwordHash, createdAt, createdAt);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return jdbcTemplate.query("SELECT * FROM users WHERE username = ?", userMapper, username).stream().findFirst();
    }

    @Override
    public Optional<User> findById(String userId) {
        return jdbcTemplate.query("SELECT * FROM users WHERE id = ?", userMapper, userId).stream().findFirst();
    }

    @Override
    public int updateBcryptCredentials(String userId, String passwordHash, String updatedAt) {
        return jdbcTemplate.update("UPDATE users SET password_hash = ?, password_salt = '', password_scheme = 'BCRYPT', " +
                        "password_version = '1', password_parameters = '{\"cost\":10}', " +
                        "rehash_required = 0, reset_required = 0, updated_at = ? WHERE id = ?",
                passwordHash, updatedAt, userId);
    }

    @Override
    public int upgradeLegacyCredentials(User expected, String passwordHash, String updatedAt) {
        return jdbcTemplate.update("UPDATE users SET password_hash = ?, password_salt = '', password_scheme = 'BCRYPT', " +
                        "password_version = '1', password_parameters = '{\"cost\":10}', " +
                        "rehash_required = 0, reset_required = 0, updated_at = ? " +
                        "WHERE id = ? AND password_scheme = ? AND password_version = ? " +
                        "AND password_hash = ? AND password_salt = ?",
                passwordHash, updatedAt, expected.getId(), expected.getPasswordScheme(), expected.getPasswordVersion(),
                expected.getPasswordHash(), expected.getPasswordSalt());
    }

    @Override
    public Optional<Principal> findPrincipal(String userId) {
        return jdbcTemplate.query("SELECT id, username, role, status FROM users WHERE id = ?",
                (rs, rowNum) -> new Principal(rs.getString("id"), rs.getString("username"),
                        rs.getString("role"), rs.getString("status")), userId).stream().findFirst();
    }

    @Override
    public Map<String, Object> findAssetSummary(String userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COUNT(DISTINCT t.id) AS saved_trips, " +
                        "COUNT(DISTINCT CASE WHEN tm.status = 'CONFIRMED' THEN tm.id END) AS memories, " +
                        "CASE WHEN up.user_id IS NULL THEN 0 ELSE 1 END AS profile_configured " +
                        "FROM users u " +
                        "LEFT JOIN trip t ON t.owner_id = u.id " +
                        "LEFT JOIN travel_memory tm ON tm.user_id = u.id " +
                        "LEFT JOIN user_preferences up ON up.user_id = u.id " +
                        "WHERE u.id = ? GROUP BY u.id, up.user_id", userId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    @Override
    public UserPage listUsers(int limit, int offset) {
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
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("username", rs.getString("username"));
                    row.put("role", rs.getString("role"));
                    row.put("status", rs.getString("status"));
                    row.put("createdAt", rs.getString("created_at"));
                    row.put("savedTrips", rs.getInt("saved_trips"));
                    row.put("memories", rs.getInt("memories"));
                    row.put("profileConfigured", rs.getInt("profile_configured") != 0);
                    return row;
                }, limit, offset);
        return new UserPage(total == null ? 0 : total, items);
    }

    @Override
    public Optional<Authority> findAuthority(String userId) {
        return jdbcTemplate.query("SELECT id, role, status FROM users WHERE id = ?",
                (rs, rowNum) -> new Authority(rs.getString("id"), rs.getString("role"), rs.getString("status")),
                userId).stream().findFirst();
    }

    @Override
    public int countOtherActiveAdministrators(String userId) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE role IN ('ADMIN','SUPER_ADMIN') " +
                "AND status = 'active' AND id <> ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    @Override
    public int updateRoleAndStatus(String userId, String role, String status, String updatedAt) {
        return jdbcTemplate.update("UPDATE users SET role = ?, status = ?, updated_at = ? WHERE id = ?",
                role, status, updatedAt, userId);
    }
}
