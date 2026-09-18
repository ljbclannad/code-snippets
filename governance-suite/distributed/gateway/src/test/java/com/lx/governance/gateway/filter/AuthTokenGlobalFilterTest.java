package com.lx.governance.gateway.filter;

import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.auth.AuthTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;
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
    @Test
    void replacesClientSuppliedRoleHeadersWithVerifiedTokenRoles() {
        String token = authTokenService.issueToken(1003L, "carol", List.of("FINANCE_VIEWER"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/order/order")
                        .header(AuthHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(AuthHeaders.USER_ID, "9999")
                        .header(AuthHeaders.USER_ROLES, "INVENTORY_VIEWER")
                        .build());
        AtomicReference<String> userId = new AtomicReference<>();
        AtomicReference<String> roles = new AtomicReference<>();

        filter.filter(exchange, next -> {
            userId.set(next.getRequest().getHeaders().getFirst(AuthHeaders.USER_ID));
            roles.set(next.getRequest().getHeaders().getFirst(AuthHeaders.USER_ROLES));
            return next.getResponse().setComplete();
        }).block();

        assertThat(userId.get()).isEqualTo("1003");
        assertThat(roles.get()).isEqualTo("FINANCE_VIEWER");
    }

    @Test
    void removesForgedIdentityHeadersFromWhitelistedRequests() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/user/login")
                        .header(AuthHeaders.USER_ID, "9999")
                        .header(AuthHeaders.USER_ROLES, "FINANCE_VIEWER")
                        .build());
        AtomicReference<String> userId = new AtomicReference<>();
        AtomicReference<String> roles = new AtomicReference<>();

        filter.filter(exchange, next -> {
            userId.set(next.getRequest().getHeaders().getFirst(AuthHeaders.USER_ID));
            roles.set(next.getRequest().getHeaders().getFirst(AuthHeaders.USER_ROLES));
            return next.getResponse().setComplete();
        }).block();

        assertThat(userId.get()).isNull();
        assertThat(roles.get()).isNull();
    }

    @Test
    void rejectsPathsThatOnlyShareAWhitelistedPrefix() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/user/login-history").build());

        filter.filter(exchange, next -> next.getResponse().setComplete()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void buyerProductSearchIsPublicAndRemovesForgedIdentity() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/search/products?q=运动鞋")
                        .header(AuthHeaders.USER_ID, "9999")
                        .header(AuthHeaders.USERNAME, "forged")
                        .header(AuthHeaders.USER_ROLES, "PRODUCT_ADMIN")
                        .build());
        AtomicReference<HttpHeaders> forwarded = new AtomicReference<>();
        filter.filter(exchange, next -> {
            forwarded.set(next.getRequest().getHeaders());
            return next.getResponse().setComplete();
        }).block();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(forwarded.get()).isNotNull();
        assertThat(forwarded.get()).doesNotContainKeys(AuthHeaders.USER_ID, AuthHeaders.USERNAME, AuthHeaders.USER_ROLES);
    }

    @Test
    void productSearchWhitelistDoesNotExposeWritesSimilarPathsOrMaintenance() {
        for (String path : List.of("/api/search/products-extra", "/api/search/products/1",
                "/api/search/products/", "/api/search/admin/rebuild", "/api/product/admin/products",
                "/search-service/products")) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
            filter.filter(exchange, next -> next.getResponse().setComplete()).block();
            assertThat(exchange.getResponse().getStatusCode()).as(path).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        MockServerWebExchange write = MockServerWebExchange.from(MockServerHttpRequest.post("/api/search/products").build());
        filter.filter(write, next -> next.getResponse().setComplete()).block();
        assertThat(write.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

}
