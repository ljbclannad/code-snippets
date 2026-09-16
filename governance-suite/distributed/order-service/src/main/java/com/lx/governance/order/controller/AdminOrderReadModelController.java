package com.lx.governance.order.controller;

import java.util.List;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;
import com.lx.governance.order.domain.AdminOrderReadModel;
import com.lx.governance.order.repo.AdminOrderReadModelQueryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/orders")
public class AdminOrderReadModelController {
    private final AdminSearchScopeResolver searchScopeResolver;
    private final AdminOrderReadModelQueryRepository repository;

    public AdminOrderReadModelController(AdminSearchScopeResolver searchScopeResolver,
            AdminOrderReadModelQueryRepository repository) {
        this.searchScopeResolver = searchScopeResolver;
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<List<AdminOrderReadModel>> list(
            @RequestHeader(value = AuthHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) String payNo,
            @RequestParam(required = false) Boolean paymentKnown,
            @RequestParam(defaultValue = "20") int limit) {
        searchScopeResolver.resolve(authorization);
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return ApiResponse.ok(repository.find(paymentStatus, payNo, paymentKnown, limit), Trace.getOrCreate());
    }
}
