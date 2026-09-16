package com.lx.governance.order.controller;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.lx.governance.common.auth.AuthTokenService;
import com.lx.governance.common.web.GlobalExceptionHandler;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;
import com.lx.governance.order.repo.AdminOrderReadModelQueryRepository;
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

    @BeforeEach
    void setUp() {
        authTokenService = new AuthTokenService(TOKEN_SECRET, 7200);
        AdminSearchScopeResolver resolver = new AdminSearchScopeResolver(authTokenService);
        AdminOrderReadModelQueryRepository repository = mock(AdminOrderReadModelQueryRepository.class);
        AdminOrderReadModelController controller = new AdminOrderReadModelController(resolver, repository);
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
}
