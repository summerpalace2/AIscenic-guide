package com.ai.guide.domain.user.security;


import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyNodeScryptVerifierTest {

    private static final String PASSWORD = "phase5b-utf8-密码";
    private static final String SALT = "0123456789abcdef0123456789abcdef";
    private static final String HASH = "b080e1ef86f32eaed77075f1608fd710049a2031c4ad253df7353491fc8294a4701538c813c1e137fcea5a16d3b34f174866a6886248003f37240c5fecfd6e68";

    private final LegacyNodeScryptVerifier verifier = new LegacyNodeScryptVerifier();

    @Test
    void matchesSyntheticNodeV1VectorWithUtf8PasswordAndSaltText() {
        assertTrue(verifier.isVerifiable(
                LegacyNodeScryptVerifier.SCHEME,
                LegacyNodeScryptVerifier.VERSION,
                LegacyNodeScryptVerifier.PARAMETERS,
                SALT,
                HASH));
        assertTrue(verifier.matches(
                PASSWORD,
                LegacyNodeScryptVerifier.SCHEME,
                LegacyNodeScryptVerifier.VERSION,
                LegacyNodeScryptVerifier.PARAMETERS,
                SALT,
                HASH));
    }

    @Test
    void wrongPasswordDoesNotMatch() {
        assertFalse(verifier.matches(
                "phase5b-wrong-password",
                LegacyNodeScryptVerifier.SCHEME,
                LegacyNodeScryptVerifier.VERSION,
                LegacyNodeScryptVerifier.PARAMETERS,
                SALT,
                HASH));
    }

    @Test
    void malformedOrUntrustedMetadataFailsClosed() {
        assertFalse(verifier.isVerifiable(
                "BCRYPT",
                LegacyNodeScryptVerifier.VERSION,
                LegacyNodeScryptVerifier.PARAMETERS,
                SALT,
                HASH));
        assertFalse(verifier.isVerifiable(
                LegacyNodeScryptVerifier.SCHEME,
                LegacyNodeScryptVerifier.VERSION,
                "{\"N\":32768,\"r\":8,\"p\":1,\"keyLength\":64}",
                SALT,
                HASH));
        assertFalse(verifier.isVerifiable(
                LegacyNodeScryptVerifier.SCHEME,
                LegacyNodeScryptVerifier.VERSION,
                LegacyNodeScryptVerifier.PARAMETERS,
                "0011",
                HASH));
        assertFalse(verifier.matches(
                PASSWORD,
                LegacyNodeScryptVerifier.SCHEME,
                LegacyNodeScryptVerifier.VERSION,
                LegacyNodeScryptVerifier.PARAMETERS,
                SALT,
                "not-a-hash"));
    }
}
