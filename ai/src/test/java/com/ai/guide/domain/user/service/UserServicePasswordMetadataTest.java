package com.ai.guide.domain.user.service;

import com.ai.guide.domain.user.gateway.JwtTokenIssuerPort;
import com.ai.guide.domain.user.gateway.UserAccountRepositoryPort;
import com.ai.guide.domain.user.gateway.PasswordEncoderPort;
import com.ai.guide.domain.user.model.User;
import com.ai.guide.infrastructure.user.security.BcryptPasswordEncoderAdapter;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServicePasswordMetadataTest {
    @Test
    void registrationCreatesExplicitBcryptAccountThroughRepository() {
        UserAccountRepositoryPort repository = mock(UserAccountRepositoryPort.class);
        JwtTokenIssuerPort tokenIssuer = mock(JwtTokenIssuerPort.class);
        String username = "metadata-user";
        when(repository.usernameExists(username)).thenReturn(false);
        when(tokenIssuer.issue(anyString(), org.mockito.ArgumentMatchers.eq(username), org.mockito.ArgumentMatchers.eq("USER")))
                .thenReturn("test-token");
        UserService service = new UserService(repository, new BcryptPasswordEncoderAdapter(), tokenIssuer, null);

        var result = service.register(username, "test-password");

        verify(repository).create(anyString(), org.mockito.ArgumentMatchers.eq(username), anyString(), anyString());
        assertEquals("USER", result.get("role"));
        assertEquals("test-token", result.get("token"));
    }

    @Test
    void unsupportedLegacySchemeFailsClosedBeforePasswordVerification() {
        UserAccountRepositoryPort repository = mock(UserAccountRepositoryPort.class);
        JwtTokenIssuerPort tokenIssuer = mock(JwtTokenIssuerPort.class);
        String username = "legacy-user";
        User legacy = new User();
        legacy.setId("legacy-id");
        legacy.setUsername(username);
        legacy.setPasswordHash("unsupported-hash");
        legacy.setPasswordScheme("LEGACY_SCRYPT");
        legacy.setPasswordVersion("node-scrypt-v1");
        legacy.setRehashRequired(true);
        legacy.setResetRequired(false);
        legacy.setRole("USER");
        legacy.setStatus("active");
        when(repository.findByUsername(username)).thenReturn(Optional.of(legacy));
        UserService service = new UserService(repository, mock(PasswordEncoderPort.class), tokenIssuer, null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.login(username, "test-password"));

        assertTrue(error.getMessage().contains("重置"));
        verify(repository, never()).upgradeLegacyCredentials(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
        verify(tokenIssuer, never()).issue(anyString(), anyString(), anyString());
    }
}
