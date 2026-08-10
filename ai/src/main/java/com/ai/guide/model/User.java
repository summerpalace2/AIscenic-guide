package com.ai.guide.model;

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
    private String role;
    private String status;
    private String nickname;
    private String avatar;
    private String phone;
    private String interests;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}