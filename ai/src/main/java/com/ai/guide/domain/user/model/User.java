package com.ai.guide.domain.user.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体
 * 原始 Python 由 sleepearlyplease 创建，Java 转化由 summerpalace2 实现
 */
@Data
public class User {
    private String id;
    private String username;
    private String passwordHash;
    /** Only populated for an un-upgraded, trusted legacy Node credential. */
    private String passwordSalt;
    private String passwordScheme;
    private String passwordVersion;
    private String passwordParameters;
    private boolean rehashRequired;
    private boolean resetRequired;
    private String role;
    private String status;
    private String nickname;
    private String avatar;
    private String phone;
    private String interests;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}