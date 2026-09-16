package com.lx.governance.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

class AuthTokenServiceTest {
    private final AuthTokenService tokenService = new AuthTokenService("test-secret", 7200);

    @Test
    void retainsOnlySupportedRolesInIssuedToken() {
        String token = tokenService.issueToken(1L, "alice", List.of("FINANCE_VIEWER", "UNKNOWN"));

        assertThat(tokenService.verify(token).getRoles()).containsExactly("FINANCE_VIEWER");
    }

    @Test
    void existingTwoArgumentIssuerCreatesTokenWithoutAdminRoles() {
        assertThat(tokenService.verify(tokenService.issueToken(1L, "alice")).getRoles()).isEmpty();
    }

    @Test
    void legacyTwoArgumentTokensDoNotSerializeRoles() {
        String token = tokenService.issueToken(1L, "alice");
        String payload = token.substring(0, token.indexOf('.'));

        assertThat(new String(Base64.getUrlDecoder().decode(payload), StandardCharsets.UTF_8))
                .doesNotContain("roles");
    }

    @Test
    void rejectsTamperedTokenPayload() {
        String token = tokenService.issueToken(1L, "alice", List.of("ORDER_OPERATOR"));

        assertThatThrownBy(() -> tokenService.verify(token + "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
