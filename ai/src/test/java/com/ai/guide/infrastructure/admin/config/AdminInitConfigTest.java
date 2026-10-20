package com.ai.guide.infrastructure.admin.config;

import com.ai.guide.domain.user.gateway.PasswordEncoderPort;
import com.ai.guide.domain.user.gateway.UserAccountRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminInitConfigTest {
    private final UserAccountRepositoryPort accounts = mock(UserAccountRepositoryPort.class);
    private final PasswordEncoderPort passwords = mock(PasswordEncoderPort.class);

    @Test void disabledModeDoesNotCreateAdministrator() throws Exception {
        when(accounts.countAdministrators()).thenReturn(0);
        var runner = config("disabled", "", "").adminInitializer();
        assertDoesNotThrow(() -> runner.run(new DefaultApplicationArguments()));
        verify(accounts, never()).createAdministrator(anyString(), anyString(), anyString(), anyString());
    }

    @Test void oneTimeModeRejectsMissingOrShortExternalPassword() {
        when(accounts.countAdministrators()).thenReturn(0);
        var runner = config("one-time", "bootstrap-admin", "short").adminInitializer();
        assertThrows(IllegalStateException.class, () -> runner.run(new DefaultApplicationArguments()));
        verify(accounts, never()).createAdministrator(anyString(), anyString(), anyString(), anyString());
    }

    @Test void oneTimeModeCreatesAdminWithEncodedExternalCredentials() throws Exception {
        when(accounts.countAdministrators()).thenReturn(0);
        when(accounts.usernameExists("bootstrap-admin")).thenReturn(false);
        when(passwords.encode("phase5a-admin-password")).thenReturn("encoded");
        ApplicationRunner runner = config("one-time", " Bootstrap-Admin ", "phase5a-admin-password").adminInitializer();
        runner.run(new DefaultApplicationArguments());
        verify(accounts).createAdministrator(anyString(), eq("bootstrap-admin"), eq("encoded"), anyString());
    }

    @Test void provisionModeCanCreateAnotherAdmin() throws Exception {
        when(accounts.countAdministrators()).thenReturn(1);
        when(accounts.usernameExists("second-admin@example.com")).thenReturn(false);
        when(passwords.encode("phase5a-admin-password")).thenReturn("encoded");
        config("provision", "second-admin@example.com", "phase5a-admin-password")
                .adminInitializer().run(new DefaultApplicationArguments());
        verify(accounts).createAdministrator(anyString(), eq("second-admin@example.com"), eq("encoded"), anyString());
    }

    private AdminInitConfig config(String mode, String username, String password) {
        return new AdminInitConfig(mode, username, password, accounts, passwords);
    }
}
