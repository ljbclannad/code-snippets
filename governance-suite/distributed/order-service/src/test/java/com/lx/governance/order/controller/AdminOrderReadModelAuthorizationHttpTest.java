package com.lx.governance.order.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.time.Instant;

import com.lx.governance.common.auth.AuthTokenService;
import com.lx.governance.common.web.GlobalExceptionHandler;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;
import com.lx.governance.order.adminauth.AdminOrderSearchViewPolicy;
import com.lx.governance.order.repo.AdminOrderReadModelQueryRepository;
import com.lx.governance.order.search.OrderOperatorSearchCursorCodec;
import com.lx.governance.order.search.OrderOperatorOrder;
import com.lx.governance.order.search.OrderOperatorSearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminOrderReadModelAuthorizationHttpTest {
    private static final String TOKEN_SECRET = "test-secret";

    private AuthTokenService authTokenService;
    private MockMvc mockMvc;
    private AdminOrderReadModelQueryRepository repository;

    @BeforeEach
    void setUp() {
        authTokenService = new AuthTokenService(TOKEN_SECRET, 7200);
        AdminSearchScopeResolver resolver = new AdminSearchScopeResolver(authTokenService);
        repository = mock(AdminOrderReadModelQueryRepository.class);
        AdminOrderReadModelController controller = new AdminOrderReadModelController(resolver, repository,
                new OrderOperatorSearchCursorCodec("cursor-secret"), new AdminOrderSearchViewPolicy());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler(), new AdminAuthorizationExceptionHandler())
                .build();
    }

    @Test
    void returnsUnauthorizedWhenAuthorizationHeaderIsMissing() throws Exception {
        mockMvc.perform(get("/admin/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void returnsForbiddenWhenTokenHasNoAdminRole() throws Exception {
        String token = authTokenService.issueToken(1L, "viewer", List.of());

        mockMvc.perform(get("/admin/orders").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ADMIN_ROLE_REQUIRED"));
    }

    @Test
    void allowsFinanceAdministratorAndReturnsFinancialView() throws Exception {
        String token = authTokenService.issueToken(1L, "finance", List.of("FINANCE_VIEWER"));
        when(repository.find(org.mockito.ArgumentMatchers.any(OrderOperatorSearchCriteria.class),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(List.of());

        mockMvc.perform(searchRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        verify(repository).find(org.mockito.ArgumentMatchers.any(OrderOperatorSearchCriteria.class),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    void returnsOperatorPageWithoutRawCustomerIdentifier() throws Exception {
        String token = authTokenService.issueToken(1L, "operator", List.of("ORDER_OPERATOR"));
        when(repository.find(org.mockito.ArgumentMatchers.any(OrderOperatorSearchCriteria.class),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(List.of(new OrderOperatorOrder("O-1", "customer-1", "token-1",
                        "P-1", 2, 100L, "CREATED", "PREPARED", "PAY-1", Instant.parse("2026-09-15T11:00:00Z"),
                        Instant.parse("2026-09-15T10:00:00Z"))));

        mockMvc.perform(searchRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].customerId").value("c***1"))
                .andExpect(jsonPath("$.data.items[0].customerSearchToken").doesNotExist())
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void removesFinancialFieldsForInventoryAdministrator() throws Exception {
        String token = authTokenService.issueToken(1L, "inventory", List.of("INVENTORY_VIEWER"));
        when(repository.find(org.mockito.ArgumentMatchers.any(OrderOperatorSearchCriteria.class),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(List.of(new OrderOperatorOrder("O-1", "customer-1", "token-1",
                        "P-1", 2, 100L, "CREATED", "PREPARED", "PAY-1", Instant.parse("2026-09-15T11:00:00Z"),
                        Instant.parse("2026-09-15T10:00:00Z"))));

        mockMvc.perform(searchRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].customerId").value("c***1"))
                .andExpect(jsonPath("$.data.items[0].amountCents").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].paymentStatus").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].payNo").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].paymentUpdatedAt").doesNotExist());
    }

    @Test
    void rejectsFinancialSortForInventoryAdministratorBeforeQueryingReadModel() throws Exception {
        String token = authTokenService.issueToken(1L, "inventory", List.of("INVENTORY_VIEWER"));

        mockMvc.perform(searchRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("sort", "AMOUNT_CENTS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("inventory administrators cannot filter or sort by financial fields"));

        verifyNoInteractions(repository);
    }

    @Test
    void forwardsNormalizedCombinedFiltersForAnOperator() throws Exception {
        String token = authTokenService.issueToken(1L, "operator", List.of("ORDER_OPERATOR"));
        when(repository.find(org.mockito.ArgumentMatchers.any(OrderOperatorSearchCriteria.class),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(List.of());

        mockMvc.perform(searchRequest()
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("orderNo", " O-1 ")
                        .param("customerSearchToken", " token-1 ")
                        .param("productId", " P-1 ")
                        .param("status", " CREATED ")
                        .param("paymentStatus", " PREPARED ")
                        .param("minAmountCents", "100")
                        .param("maxAmountCents", "200")
                        .param("limit", "1"))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<OrderOperatorSearchCriteria> criteria = org.mockito.ArgumentCaptor.forClass(
                OrderOperatorSearchCriteria.class);
        verify(repository).find(criteria.capture(), org.mockito.ArgumentMatchers.isNull());
        assertThat(criteria.getValue()).isEqualTo(OrderOperatorSearchCriteria.create("O-1", "token-1", "P-1", "CREATED",
                "PREPARED", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"), 100L, 200L, 1));
    }

    @Test
    void rejectsInvalidLimitWithStableBadRequest() throws Exception {
        String token = authTokenService.issueToken(1L, "operator", List.of("ORDER_OPERATOR"));

        mockMvc.perform(searchRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("limit must be between 1 and 100"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsTamperedCursorWithStableBadRequest() throws Exception {
        String token = authTokenService.issueToken(1L, "operator", List.of("ORDER_OPERATOR"));

        mockMvc.perform(searchRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + token).param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("invalid cursor"));

        verifyNoInteractions(repository);
    }

    @Test
    void rejectsCreatedTimeRangeLongerThanNinetyDays() throws Exception {
        String token = authTokenService.issueToken(1L, "operator", List.of("ORDER_OPERATOR"));

        mockMvc.perform(get("/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("createdFrom", "2026-06-01T00:00:00Z")
                        .param("createdTo", "2026-09-01T00:00:01Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("created time range must not exceed 90 days"));
    }

    @Test
    void rejectsMalformedCreatedTimeWithStableBadRequest() throws Exception {
        String token = authTokenService.issueToken(1L, "operator", List.of("ORDER_OPERATOR"));

        mockMvc.perform(get("/admin/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("createdFrom", "not-a-time")
                        .param("createdTo", "2026-09-02T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("invalid request parameter"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder searchRequest() {
        return get("/admin/orders")
                .param("createdFrom", "2026-09-01T00:00:00Z")
                .param("createdTo", "2026-09-02T00:00:00Z");
    }
}
