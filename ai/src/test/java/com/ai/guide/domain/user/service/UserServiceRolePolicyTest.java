package com.ai.guide.domain.user.service;

import com.ai.guide.domain.user.gateway.JwtTokenIssuerPort;
import com.ai.guide.domain.user.gateway.UserAccountRepositoryPort;
import com.ai.guide.domain.user.gateway.PasswordEncoderPort;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceRolePolicyTest {
    @Test
    void currentDatabaseRoleAndStatusAreValidatedAtJwtBoundary() {
        UserAccountRepositoryPort repository = mock(UserAccountRepositoryPort.class);
        UserService service = new UserService(repository, mock(PasswordEncoderPort.class), mock(JwtTokenIssuerPort.class), null);
        when(repository.findPrincipal("disabled-user")).thenReturn(Optional.of(
                new UserAccountRepositoryPort.Principal("disabled-user", "disabled@example.invalid", "USER", "disabled")));
        when(repository.findPrincipal("unknown-role-user")).thenReturn(Optional.of(
                new UserAccountRepositoryPort.Principal("unknown-role-user", "unknown@example.invalid", "OWNER", "active")));
        when(repository.findPrincipal("active-user")).thenReturn(Optional.of(
                new UserAccountRepositoryPort.Principal("active-user", "active@example.invalid", "ADMIN", "active")));

        assertNull(service.findActivePrincipal("disabled-user"));
        assertNull(service.findActivePrincipal("unknown-role-user"));
        assertEquals("ADMIN", service.findActivePrincipal("active-user").role());
    }

    @Test
    void invalidRoleOrStatusIsRejectedBeforeMutation() {
        UserAccountRepositoryPort repository = mock(UserAccountRepositoryPort.class);
        UserService service = new UserService(repository, mock(PasswordEncoderPort.class), mock(JwtTokenIssuerPort.class), null);
        when(repository.findAuthority("user-1")).thenReturn(Optional.of(
                new UserAccountRepositoryPort.Authority("user-1", "USER", "active")));

        assertThrows(IllegalArgumentException.class, () -> service.updateUser("user-1", "OWNER", null));
        assertThrows(IllegalArgumentException.class, () -> service.updateUser("user-1", null, "pending"));
        verify(repository, never()).updateRoleAndStatus(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void finalActiveAdministratorCannotBeDemotedOrDisabled() {
        UserAccountRepositoryPort repository = mock(UserAccountRepositoryPort.class);
        UserService service = new UserService(repository, mock(PasswordEncoderPort.class), mock(JwtTokenIssuerPort.class), null);
        when(repository.findAuthority("admin-1")).thenReturn(Optional.of(
                new UserAccountRepositoryPort.Authority("admin-1", "ADMIN", "active")));
        when(repository.countOtherActiveAdministrators("admin-1")).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> service.updateUser("admin-1", "USER", null));
        assertThrows(IllegalArgumentException.class, () -> service.updateUser("admin-1", null, "disabled"));
        verify(repository, never()).updateRoleAndStatus(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void administratorMayChangeWhenAnotherActiveAdministratorRemains() {
        UserAccountRepositoryPort repository = mock(UserAccountRepositoryPort.class);
        UserService service = new UserService(repository, mock(PasswordEncoderPort.class), mock(JwtTokenIssuerPort.class), null);
        when(repository.findAuthority("admin-1")).thenReturn(Optional.of(
                new UserAccountRepositoryPort.Authority("admin-1", "ADMIN", "active")));
        when(repository.countOtherActiveAdministrators("admin-1")).thenReturn(1);
        when(repository.updateRoleAndStatus(anyString(), anyString(), anyString(), anyString())).thenReturn(1);

        service.updateUser("admin-1", "USER", null);

        verify(repository).updateRoleAndStatus(org.mockito.ArgumentMatchers.eq("admin-1"),
                org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.eq("active"), anyString());
    }
}
