package com.ai.guide.domain.user.service;


import com.ai.guide.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceRolePolicyTest {

    @Test
    void currentDatabaseRoleAndStatusAreValidatedAtJwtBoundary() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UserService service = new UserService(jdbcTemplate, mock(JwtUtil.class));
        String query = "SELECT id, username, role, status FROM users WHERE id = ?";

        when(jdbcTemplate.query(eq(query), any(RowMapper.class), eq("disabled-user")))
                .thenReturn(List.of(new UserService.AuthenticatedPrincipal(
                        "disabled-user", "disabled@example.invalid", "USER", "disabled")));
        when(jdbcTemplate.query(eq(query), any(RowMapper.class), eq("unknown-role-user")))
                .thenReturn(List.of(new UserService.AuthenticatedPrincipal(
                        "unknown-role-user", "unknown@example.invalid", "OWNER", "active")));
        when(jdbcTemplate.query(eq(query), any(RowMapper.class), eq("active-user")))
                .thenReturn(List.of(new UserService.AuthenticatedPrincipal(
                        "active-user", "active@example.invalid", "ADMIN", "active")));

        assertNull(service.findActivePrincipal("disabled-user"));
        assertNull(service.findActivePrincipal("unknown-role-user"));
        assertEquals("ADMIN", service.findActivePrincipal("active-user").role());
    }

    @Test
    void invalidRoleOrStatusIsRejectedBeforeMutation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UserService service = new UserService(jdbcTemplate, mock(JwtUtil.class));
        when(jdbcTemplate.queryForList(anyString(), eq("user-1")))
                .thenReturn(List.of(Map.of("id", "user-1", "role", "USER", "status", "active")));

        assertThrows(IllegalArgumentException.class, () -> service.updateUser("user-1", "OWNER", null));
        assertThrows(IllegalArgumentException.class, () -> service.updateUser("user-1", null, "pending"));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void finalActiveAdministratorCannotBeDemotedOrDisabled() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UserService service = new UserService(jdbcTemplate, mock(JwtUtil.class));
        when(jdbcTemplate.queryForList(anyString(), eq("admin-1")))
                .thenReturn(List.of(Map.of("id", "admin-1", "role", "ADMIN", "status", "active")));
        when(jdbcTemplate.queryForObject(contains("COUNT(*)"), eq(Integer.class), eq("admin-1")))
                .thenReturn(0);

        assertThrows(IllegalArgumentException.class,
                () -> service.updateUser("admin-1", "USER", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateUser("admin-1", null, "disabled"));
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void administratorMayChangeWhenAnotherActiveAdministratorRemains() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UserService service = new UserService(jdbcTemplate, mock(JwtUtil.class));
        when(jdbcTemplate.queryForList(anyString(), eq("admin-1")))
                .thenReturn(List.of(Map.of("id", "admin-1", "role", "ADMIN", "status", "active")));
        when(jdbcTemplate.queryForObject(contains("COUNT(*)"), eq(Integer.class), eq("admin-1")))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE users SET role"), any(Object[].class))).thenReturn(1);

        service.updateUser("admin-1", "USER", null);

        verify(jdbcTemplate).update(contains("UPDATE users SET role"), any(Object[].class));
    }
}
