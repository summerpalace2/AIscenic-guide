package com.ai.guide.domain.user.security;

import org.bouncycastle.crypto.generators.SCrypt;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
/**
 * Verifies the narrowly defined Node persistent-store v1 password contract.
 *
 * <p>The legacy implementation passes the stored salt text directly to
 * {@code scryptSync}; the hexadecimal-looking salt is not decoded first. This
 * adapter intentionally accepts no caller-controlled cost parameters.</p>
 */
public /**
 * 兼容旧 Node.js 端 Scrypt 密码哈希校验器
 *
 * 所属领域：domain.user.security（用户安全与加密）
 * 架构职责：对尚未升级到标准 BCrypt 的旧系统用户密码进行 Scrypt 比对，校验成功后触发自动重哈希平滑升级。
 */
final class LegacyNodeScryptVerifier {

    public static final String SCHEME = "LEGACY_SCRYPT";
    public static final String VERSION = "node-scrypt-v1";
    public static final String PARAMETERS = "{\"N\":16384,\"r\":8,\"p\":1,\"keyLength\":64}";

    private static final int COST = 16_384;
    private static final int BLOCK_SIZE = 8;
    private static final int PARALLELIZATION = 1;
    private static final int KEY_LENGTH = 64;
    private static final HexFormat HEX = HexFormat.of();

    /** Returns true only for the complete, trusted Node-v1 credential shape. */
    public boolean isVerifiable(String scheme, String version, String parameters,
                                String salt, String hash) {
        return SCHEME.equalsIgnoreCase(text(scheme))
                && VERSION.equals(text(version))
                && PARAMETERS.equals(text(parameters))
                && isSaltShapeValid(salt)
                && isHashShapeValid(hash);
    }

    /**
     * Performs a constant-time verification after strict metadata validation.
     * Invalid metadata is treated as an unsupported credential and returns
     * false; callers must fail closed and require reset.
     */
    public boolean matches(String password, String scheme, String version, String parameters,
                           String salt, String hash) {
        if (password == null || !isVerifiable(scheme, version, parameters, salt, hash)) {
            return false;
        }

        byte[] expected;
        try {
            expected = HEX.parseHex(hash);
        } catch (IllegalArgumentException invalidHex) {
            return false;
        }

        byte[] actual;
        try {
            actual = SCrypt.generate(
                    password.getBytes(StandardCharsets.UTF_8),
                    salt.getBytes(StandardCharsets.UTF_8),
                    COST,
                    BLOCK_SIZE,
                    PARALLELIZATION,
                    KEY_LENGTH);
        } catch (RuntimeException derivationFailure) {
            return false;
        }
        return MessageDigest.isEqual(actual, expected);
    }

    public static boolean isSaltShapeValid(String salt) {
        return salt != null && salt.matches("[0-9a-fA-F]{32}");
    }

    public static boolean isHashShapeValid(String hash) {
        return hash != null && hash.matches("[0-9a-fA-F]{128}");
    }

    public static String canonicalParameters() {
        return PARAMETERS;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
