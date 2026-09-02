package com.ai.guide.common.context;

import com.ai.guide.common.security.JwtAuthFilter;

/**
 * 线程级用户鉴权上下文
 *
 * 所属领域：common.context（通用基础设施层）
 * 架构职责：基于 ThreadLocal 在同一请求线程生命周期中安全传递已鉴权用户的身份标识 (userId)、用户名 (username)、角色权限 (role)，并提供匿名请求判定方法。
 *
 * 核心方法与职责：
 * 1. set：在 JwtAuthFilter 中设置当前请求的身份上下文
 * 2. getUserId：获取当前用户 ID（匿名请求返回 null）
 * 3. isAdmin：判定当前用户是否具备 ADMIN 或 SUPER_ADMIN 管理员权限
 * 4. clear：在请求结束时清理 ThreadLocal，防止线程池线程复用导致内存泄漏或身份污染
 */
public class UserContext {

    private static final ThreadLocal<String> USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ROLE = new ThreadLocal<>();

    public static void set(String userId, String role) {
        set(userId, null, role);
    }

    public static void set(String userId, String username, String role) {
        USER_ID.set(userId);
        USERNAME.set(username);
        USER_ROLE.set(role);
    }

    public static String getUserId() {
        // Anonymous requests have no durable owner identity. Callers that need
        // an owner must reject the request or use an explicit guest capability.
        return USER_ID.get();
    }

    /**
     * 是否需要强制登录（admin 接口为 true）
     */
    public static boolean isAnonymous() {
        return USER_ID.get() == null;
    }

    public static String getRole() {
        String r = USER_ROLE.get();
        return r != null ? r : "TOURIST";
    }

    public static String getUsername() {
        String username = USERNAME.get();
        return username != null ? username : getUserId();
    }

    public static boolean isAdmin() {
        return "ADMIN".equals(getRole()) || "SUPER_ADMIN".equals(getRole());
    }

    public static void clear() {
        USER_ID.remove();
        USERNAME.remove();
        USER_ROLE.remove();
    }
}
