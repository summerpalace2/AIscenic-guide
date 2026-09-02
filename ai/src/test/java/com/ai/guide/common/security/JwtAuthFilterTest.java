package com.ai.guide.common.security;

import com.ai.guide.common.context.UserContext;
import com.ai.guide.domain.user.service.UserService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    private final JwtUtil jwtUtil = mock(JwtUtil.class);
    private final UserService userService = mock(UserService.class);
    private final JwtRevocationService revocationService = mock(JwtRevocationService.class);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtUtil, userService, revocationService);

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void protectedRouteWithoutHeaderReturns401BeforeController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/trips");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        assertFalse(chain.getRequest() != null);
    }

    @Test
    void authenticatedOrdinaryUserGetsRealForbiddenOnAdminRoute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/admin/users");
        request.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        Claims claims = mock(Claims.class);
        when(jwtUtil.parse("user-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("user-1");
        when(revocationService.isRevoked("user-token", claims)).thenReturn(false);
        when(userService.findActivePrincipal("user-1"))
                .thenReturn(new UserService.AuthenticatedPrincipal("user-1", "traveler", "USER", "active"));

        filter.doFilterInternal(request, response, chain);

        assertEquals(403, response.getStatus());
        assertFalse(chain.getRequest() != null);
    }

    @Test
    void explicitPublicRouteMayBeAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/attractions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
        assertTrue(UserContext.isAnonymous());
    }

    @Test
    void guestChatStreamMayBeAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/chat/stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
        assertTrue(UserContext.isAnonymous());
    }

    @Test
    void unknownNestedAttractionRouteIsNotAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/attractions/cq-1/admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        assertFalse(chain.getRequest() != null);
    }

    @Test
    void invalidBearerIs401EvenOnPublicRoute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/attractions");
        request.addHeader("Authorization", "Bearer malformed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        when(jwtUtil.parse("malformed")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        assertFalse(chain.getRequest() != null);
    }

    @Test
    void validTokenUsesCurrentDatabaseAuthorityAndClearsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/trips");
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Claims claims = mock(Claims.class);
        when(jwtUtil.parse("valid-token")).thenReturn(claims);
        when(claims.getSubject()).thenReturn("user-1");
        when(revocationService.isRevoked("valid-token", claims)).thenReturn(false);
        when(userService.findActivePrincipal("user-1"))
                .thenReturn(new UserService.AuthenticatedPrincipal("user-1", "traveler", "USER", "active"));
        FilterChain chain = (req, res) -> {
            assertEquals("user-1", UserContext.getUserId());
            assertEquals("USER", UserContext.getRole());
        };

        filter.doFilterInternal(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(UserContext.isAnonymous());
        verify(userService).findActivePrincipal("user-1");
    }

    @Test
    void revokedTokenReturns401AndDoesNotReachController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ai/trips");
        request.addHeader("Authorization", "Bearer revoked-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        Claims claims = mock(Claims.class);
        when(jwtUtil.parse("revoked-token")).thenReturn(claims);
        when(revocationService.isRevoked("revoked-token", claims)).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        assertEquals(401, response.getStatus());
        assertFalse(chain.getRequest() != null);
        verify(userService, never()).findActivePrincipal("user-1");
    }

    @Test
    void optionsPreflightPassesWithoutCredentials() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/ai/trips");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertEquals(200, response.getStatus());
        assertTrue(chain.getRequest() != null);
    }
}
