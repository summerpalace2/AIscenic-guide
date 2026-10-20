package com.ai.guide.domain.user.gateway;

/** Verification and revocation capability for authentication session tokens. */
public interface JwtSessionTokenPort {
    boolean isValid(String token);
    boolean revoke(String token);
}
