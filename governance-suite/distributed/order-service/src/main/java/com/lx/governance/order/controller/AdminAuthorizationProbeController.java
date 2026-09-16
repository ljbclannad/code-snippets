package com.lx.governance.order.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.adminauth.AdminSearchScope;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;

@RestController
@RequestMapping("/admin")
public class AdminAuthorizationProbeController {
    private final AdminSearchScopeResolver adminSearchScopeResolver;

    public AdminAuthorizationProbeController(AdminSearchScopeResolver adminSearchScopeResolver) {
        this.adminSearchScopeResolver = adminSearchScopeResolver;
    }

    @GetMapping("/search-scope")
    public ApiResponse<AdminSearchScope> searchScope(
            @RequestHeader(value = AuthHeaders.AUTHORIZATION, required = false) String authorization) {
        return ApiResponse.ok(adminSearchScopeResolver.resolve(authorization), Trace.getOrCreate());
    }
}
