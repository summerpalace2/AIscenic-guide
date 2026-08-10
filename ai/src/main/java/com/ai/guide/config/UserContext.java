package com.ai.guide.config;

/**
 * 用户上下文（ThreadLocal）
 * 在 JwtAuthFilter 中设置，Controller/Service 中读取
 * 原始 Python 由 sleepearlyplease 实现，Java 转化由 summerpalace2 实现
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
        String id = USER_ID.get();
        return id != null ? id : "anonymous";
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
