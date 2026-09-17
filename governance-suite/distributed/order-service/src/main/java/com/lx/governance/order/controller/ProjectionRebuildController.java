package com.lx.governance.order.controller;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.rebuild.ProjectionRebuildService;
import com.lx.governance.order.rebuild.ProjectionRebuildTask;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/orders/rebuilds")
public class ProjectionRebuildController {
    private final ProjectionRebuildService service;

    public ProjectionRebuildController(ProjectionRebuildService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<ProjectionRebuildTask>> create(
            @RequestHeader(value = AuthHeaders.AUTHORIZATION, required = false) String authorization) {
        return ResponseEntity.accepted().body(ApiResponse.ok(service.create(authorization), Trace.getOrCreate()));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectionRebuildTask> get(
            @RequestHeader(value = AuthHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String id) {
        return ApiResponse.ok(service.get(authorization, id), Trace.getOrCreate());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(
            @RequestHeader(value = AuthHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String id) {
        service.retry(authorization, id);
        return ResponseEntity.accepted().build();
    }
}
