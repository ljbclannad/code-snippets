package com.lx.governance.order.adminauth;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.lx.governance.common.auth.AdminRole;
import com.lx.governance.common.auth.AuthTokenClaims;
import com.lx.governance.common.auth.AuthTokenService;

@Component
public class AdminSearchScopeResolver {
    private final AuthTokenService authTokenService;

    public AdminSearchScopeResolver(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    public AdminSearchScope resolve(String authorization) {
        final AuthTokenClaims claims;
        try {
            claims = authTokenService.verify(authorization);
        } catch (IllegalArgumentException exception) {
            throw new AdminAuthenticationException(exception.getMessage());
        }
        Set<String> roles = AdminRole.normalize(claims.getRoles());
        if (roles.isEmpty()) {
            throw new AdminRoleRequiredException();
        }
        VerifiedAdminIdentity identity = new VerifiedAdminIdentity(claims.getUserId(), claims.getUsername(), roles);
        return new AdminSearchScope(identity.userId(), identity.username(), identity.roles());
    }
}
