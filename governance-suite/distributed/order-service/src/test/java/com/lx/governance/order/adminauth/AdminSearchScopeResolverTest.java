package com.lx.governance.order.adminauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.lx.governance.common.auth.AuthTokenService;

class AdminSearchScopeResolverTest {
    private final AuthTokenService tokenService = new AuthTokenService("test-secret", 7200);
    private final AdminSearchScopeResolver resolver = new AdminSearchScopeResolver(tokenService);

    @Test
    void resolvesScopeFromVerifiedTokenInsteadOfHeaders() {
        String token = tokenService.issueToken(42L, "finance", List.of("FINANCE_VIEWER"));

        AdminSearchScope scope = resolver.resolve("Bearer " + token);

        assertThat(scope.userId()).isEqualTo(42L);
        assertThat(scope.roles()).containsExactly("FINANCE_VIEWER");
    }

    @Test
    void rejectsTokenWithoutAnAdminRole() {
        assertThatThrownBy(() -> resolver.resolve(tokenService.issueToken(42L, "customer")))
                .isInstanceOf(AdminRoleRequiredException.class);
    }

    @Test
    void rejectsMissingToken() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(AdminAuthenticationException.class);
    }
}
