package com.ai.guide.domain.user.service;


import com.ai.guide.domain.user.gateway.JwtTokenIssuerPort;
import com.ai.guide.domain.user.gateway.PasswordEncoderPort;
import com.ai.guide.domain.user.model.User;
import com.ai.guide.domain.user.gateway.UserAccountRepositoryPort;
import com.ai.guide.infrastructure.user.security.LegacyNodeScryptVerifier;
import com.ai.guide.infrastructure.user.security.BcryptPasswordEncoderAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceLegacyPasswordMigrationTest {

    private static final String PASSWORD = "phase5b-utf8-密码";
    private static final String SALT = "0123456789abcdef0123456789abcdef";
    private static final String HASH = "b080e1ef86f32eaed77075f1608fd710049a2031c4ad253df7353491fc8294a4701538c813c1e137fcea5a16d3b34f174866a6886248003f37240c5fecfd6e68";

    @Test
    void successfulLegacyLoginRehashesAndIssuesToken() {
        UserAccountRepositoryPort repository = mock(UserAccountRepositoryPort.class);
        JwtTokenIssuerPort tokenIssuer = mock(JwtTokenIssuerPort.class);
        String username = "legacy-migration@example.invalid";
        User legacy = legacyUser(username);
        when(repository.findByUsername(username)).thenReturn(java.util.Optional.of(legacy));
        when(repository.upgradeLegacyCredentials(any(User.class), anyString(), anyString())).thenReturn(1);
        when(tokenIssuer.issue(eq("legacy-id"), eq(username), eq("USER")))
                .thenReturn("legacy-token");

        UserService service = new UserService(repository, new BcryptPasswordEncoderAdapter(), tokenIssuer, new LegacyNodeScryptVerifier());
        Map<String, Object> result = service.login(username, PASSWORD);

        assertEquals("legacy-token", result.get("token"));
        assertEquals("USER", result.get("role"));
        verify(repository).upgradeLegacyCredentials(any(User.class), anyString(), anyString());
    }

    @Test
    void wrongLegacyPasswordDoesNotMutateOrIssueToken() {
        UserAccountRepositoryPort repository = mock(UserAccountRepositoryPort.class);
        JwtTokenIssuerPort tokenIssuer = mock(JwtTokenIssuerPort.class);
        String username = "legacy-wrong@example.invalid";
        when(repository.findByUsername(username)).thenReturn(java.util.Optional.of(legacyUser(username)));

        UserService service = new UserService(repository, new BcryptPasswordEncoderAdapter(), tokenIssuer, new LegacyNodeScryptVerifier());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.login(username, "phase5b-wrong-password"));

        assertTrue(error.getMessage().contains("密码错误"));
        verify(repository, never()).upgradeLegacyCredentials(any(User.class), anyString(), anyString());
        verify(tokenIssuer, never()).issue(anyString(), anyString(), anyString());
    }

    @Test
    void secondLoginUsesTheNewBcryptCredential() {
        UserAccountRepositoryPort repository = mock(UserAccountRepositoryPort.class);
        JwtTokenIssuerPort tokenIssuer = mock(JwtTokenIssuerPort.class);
        String username = "legacy-second-login@example.invalid";
        User legacy = legacyUser(username);
        User bcrypt = new User();
        bcrypt.setId("legacy-id");
        bcrypt.setUsername(username);
        bcrypt.setPasswordHash(new BCryptPasswordEncoder().encode(PASSWORD));
        bcrypt.setPasswordScheme("BCRYPT");
        bcrypt.setPasswordVersion("1");
        bcrypt.setPasswordParameters("{\"cost\":10}");
        bcrypt.setRehashRequired(false);
        bcrypt.setResetRequired(false);
        bcrypt.setRole("USER");
        bcrypt.setStatus("active");
        when(repository.findByUsername(username)).thenReturn(java.util.Optional.of(legacy), java.util.Optional.of(bcrypt));
        when(repository.upgradeLegacyCredentials(any(User.class), anyString(), anyString())).thenReturn(1);
        when(tokenIssuer.issue(eq("legacy-id"), eq(username), eq("USER")))
                .thenReturn("legacy-token", "bcrypt-token");

        UserService service = new UserService(repository, new BcryptPasswordEncoderAdapter(), tokenIssuer, new LegacyNodeScryptVerifier());
        service.login(username, PASSWORD);
        Map<String, Object> second = service.login(username, PASSWORD);

        assertEquals("bcrypt-token", second.get("token"));
        verify(tokenIssuer, org.mockito.Mockito.times(2))
                .issue(eq("legacy-id"), eq(username), eq("USER"));
    }

    private User legacyUser(String username) {
        User user = new User();
        user.setId("legacy-id");
        user.setUsername(username);
        user.setPasswordHash(HASH);
        user.setPasswordSalt(SALT);
        user.setPasswordScheme(LegacyNodeScryptVerifier.SCHEME);
        user.setPasswordVersion(LegacyNodeScryptVerifier.VERSION);
        user.setPasswordParameters(LegacyNodeScryptVerifier.PARAMETERS);
        user.setRehashRequired(true);
        user.setResetRequired(false);
        user.setRole("USER");
        user.setStatus("active");
        return user;
    }
}
