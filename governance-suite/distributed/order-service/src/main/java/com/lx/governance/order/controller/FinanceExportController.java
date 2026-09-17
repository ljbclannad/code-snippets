package com.lx.governance.order.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.export.FinanceExportService;
import com.lx.governance.order.export.FinanceExportTask;
import com.lx.governance.order.search.OrderOperatorSearchCriteria;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/orders/exports")
public class FinanceExportController {
    private final FinanceExportService service;

    public FinanceExportController(FinanceExportService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ApiResponse<FinanceExportTask>> create(
            @RequestHeader(value = AuthHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String orderNo, @RequestParam(required = false) String customerSearchToken,
            @RequestParam(required = false) String productId, @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus, @RequestParam Instant createdFrom,
            @RequestParam Instant createdTo, @RequestParam(required = false) Long minAmountCents,
            @RequestParam(required = false) Long maxAmountCents, @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction) {
        OrderOperatorSearchCriteria criteria = OrderOperatorSearchCriteria.create(orderNo, customerSearchToken, productId, status,
                paymentStatus, createdFrom, createdTo, minAmountCents, maxAmountCents, sort, direction, 100);
        return ResponseEntity.accepted().body(ApiResponse.ok(service.create(authorization, criteria), Trace.getOrCreate()));
    }

    @GetMapping("/{id}")
    public ApiResponse<FinanceExportTask> get(@RequestHeader(value = AuthHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String id) {
        return ApiResponse.ok(service.get(authorization, id), Trace.getOrCreate());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@RequestHeader(value = AuthHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String id) {
        service.retry(authorization, id);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<FileSystemResource> download(@RequestHeader(value = AuthHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String id) throws IOException {
        Path file = service.download(authorization, id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .contentLength(Files.size(file)).header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("finance-orders-" + id + ".csv").build().toString())
                .body(new FileSystemResource(file));
    }
}
