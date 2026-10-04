package com.ai.guide.domain.user.gateway;

/**
 * 历史遗留密码校验网关端口 (DIP 依赖倒置契约)
 *
 * 所属领域：domain.user.gateway
 * 架构职责：定义对旧系统（如 Node.js Scrypt）遗留密码校验的统一领域抽象，消除领域层对具体密码学实现库的直接依赖。
 */
public interface LegacyPasswordVerifierPort {

    String SCHEME = "LEGACY_SCRYPT";
    String VERSION = "node-scrypt-v1";

    boolean isVerifiable(String scheme, String version, String parameters, String salt, String hash);

    boolean matches(String password, String scheme, String version, String parameters, String salt, String hash);
}
