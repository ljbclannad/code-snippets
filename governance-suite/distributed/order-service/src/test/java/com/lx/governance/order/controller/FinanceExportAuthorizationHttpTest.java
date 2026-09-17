package com.lx.governance.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.lx.governance.common.auth.AuthTokenService;
import com.lx.governance.common.web.GlobalExceptionHandler;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;
import com.lx.governance.order.export.FinanceExportService;
import com.lx.governance.order.export.FinanceExportStatus;
import com.lx.governance.order.export.FinanceExportTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FinanceExportAuthorizationHttpTest {
    private AuthTokenService tokenService;
    private FinanceExportService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tokenService = new AuthTokenService("test-secret", 7200);
        service = mock(FinanceExportService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new FinanceExportController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new GlobalExceptionHandler(), new AdminAuthorizationExceptionHandler()).build();
    }

    @Test
    void acceptsFinanceCreationAndReturnsAccepted() throws Exception {
        FinanceExportTask task = new FinanceExportTask("task", 1L, FinanceExportStatus.PENDING, "fp", null, null,
                null, null, null, null, null, 0, null, null, Instant.now(), null, null);
        when(service.create(any(), any())).thenReturn(task);
        String token = tokenService.issueToken(1L, "finance", List.of("FINANCE_VIEWER"));

        mockMvc.perform(createRequest().header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.id").value("task"));
    }

    @Test
    void passesMissingAuthorizationToServiceForReverification() throws Exception {
        when(service.create(eq(null), any())).thenThrow(new com.lx.governance.order.adminauth.AdminAuthenticationException("missing authorization"));
        mockMvc.perform(createRequest()).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void returnsNotFoundForAnotherCreatorsTaskWithoutLeakingIt() throws Exception {
        String token = tokenService.issueToken(2L, "other-finance", List.of("FINANCE_VIEWER"));
        when(service.get(any(), eq("hidden"))).thenThrow(new FinanceExportService.FinanceExportNotFoundException());
        mockMvc.perform(get("/admin/orders/exports/hidden").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("EXPORT_NOT_FOUND"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createRequest() {
        return post("/admin/orders/exports").param("createdFrom", "2026-09-01T00:00:00Z")
                .param("createdTo", "2026-09-02T00:00:00Z");
    }
}
