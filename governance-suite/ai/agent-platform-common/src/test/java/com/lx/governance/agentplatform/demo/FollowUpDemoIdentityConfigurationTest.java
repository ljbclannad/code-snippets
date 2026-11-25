package com.lx.governance.agentplatform.demo;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import static org.junit.jupiter.api.Assertions.*;

class FollowUpDemoIdentityConfigurationTest {
    @Test void loginBindsOnlyConfiguredRoleAndRejectsCrossOriginWrites() throws Exception {
        var config = new FollowUpDemoIdentityConfiguration();
        assertThrows(IllegalArgumentException.class,
                () -> config.followUpDemoIdentity("0.0.0.0", "PATIENT", "demo-patient", "synthetic-password"));
        var filter = config.followUpDemoIdentity("127.0.0.1", "PATIENT", "demo-patient", "synthetic-password");
        var login = new MockHttpServletRequest("POST", "/demo-login");
        login.setParameter("account", "demo-patient");
        login.setParameter("password", "synthetic-password");
        var loginResponse = new MockHttpServletResponse();
        filter.doFilter(login, loginResponse, (request, response) -> fail("登录由演示过滤器处理"));
        assertEquals(303, loginResponse.getStatus());

        var patient = new MockHttpServletRequest("GET", "/api/patient/session");
        patient.setSession((MockHttpSession) login.getSession(false));
        filter.doFilter(patient, new MockHttpServletResponse(), (request, response) -> {
            HttpServletRequest caller = (HttpServletRequest) request;
            assertEquals("demo-patient", caller.getUserPrincipal().getName());
            assertTrue(caller.isUserInRole("PATIENT"));
            assertFalse(caller.isUserInRole("DOCTOR"));
        });

        var crossOrigin = new MockHttpServletRequest("POST", "/api/chat");
        crossOrigin.setSession((MockHttpSession) login.getSession(false));
        crossOrigin.addHeader("Origin", "http://localhost:9000");
        var denied = new MockHttpServletResponse();
        filter.doFilter(crossOrigin, denied, (request, response) -> fail("跨源请求应被拦截"));
        assertEquals(403, denied.getStatus());
    }
}
