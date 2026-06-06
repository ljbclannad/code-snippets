package com.lx.governance.gateway.filter;

import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.auth.AuthTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AuthTokenGlobalFilterTest {

    private final AuthTokenService authTokenService = new AuthTokenService("test-secret", 7200);
    private final AuthTokenGlobalFilter filter = new AuthTokenGlobalFilter(authTokenService);

    @Test
    void websocketHandshakeCanAuthenticateWithQueryToken() {
        String token = authTokenService.issueToken(1001L, "alice");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ws/appointment")
                        .queryParam("token", token)
                        .build());
        AtomicReference<String> userId = new AtomicReference<>();
        AtomicReference<String> username = new AtomicReference<>();

        filter.filter(exchange, next -> {
            userId.set(next.getRequest().getHeaders().getFirst(AuthHeaders.USER_ID));
            username.set(next.getRequest().getHeaders().getFirst(AuthHeaders.USERNAME));
            return next.getResponse().setComplete();
        }).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(userId.get()).isEqualTo("1001");
        assertThat(username.get()).isEqualTo("alice");
    }

    @Test
    void normalApiRequestsStillRequireAuthorizationHeader() {
        String token = authTokenService.issueToken(1002L, "bob");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/appointment/schedules")
                        .queryParam("token", token)
                        .build());

        filter.filter(exchange, next -> next.getResponse().setComplete()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
