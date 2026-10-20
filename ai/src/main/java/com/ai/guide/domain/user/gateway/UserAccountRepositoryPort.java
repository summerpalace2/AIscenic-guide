package com.ai.guide.domain.user.gateway;

import com.ai.guide.domain.user.model.User;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persistence boundary for account credentials, authority and account listings. */
public interface UserAccountRepositoryPort {
    record Principal(String id, String username, String role, String status) {}
    record Authority(String id, String role, String status) {}
    record UserPage(long total, List<Map<String, Object>> items) {}

    boolean usernameExists(String username);
    int countAdministrators();
    void createAdministrator(String id, String username, String passwordHash, String createdAt);
    void create(String id, String username, String passwordHash, String createdAt);
    Optional<User> findByUsername(String username);
    Optional<User> findById(String userId);
    int updateBcryptCredentials(String userId, String passwordHash, String updatedAt);
    int upgradeLegacyCredentials(User expected, String passwordHash, String updatedAt);
    Optional<Principal> findPrincipal(String userId);
    Map<String, Object> findAssetSummary(String userId);
    UserPage listUsers(int limit, int offset);
    Optional<Authority> findAuthority(String userId);
    int countOtherActiveAdministrators(String userId);
    int updateRoleAndStatus(String userId, String role, String status, String updatedAt);
}
