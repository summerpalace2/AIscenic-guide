package com.ai.guide.domain.user.gateway;

/** Capability for issuing signed authentication tokens. */
public interface JwtTokenIssuerPort {
    String issue(String userId, String username, String role);
}
