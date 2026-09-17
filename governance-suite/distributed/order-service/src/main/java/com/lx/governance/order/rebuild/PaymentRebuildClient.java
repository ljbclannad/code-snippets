package com.lx.governance.order.rebuild;

import java.time.Instant;
import java.util.List;

import com.lx.governance.common.api.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "payment-service", contextId = "paymentRebuildClient")
public interface PaymentRebuildClient {
    record Watermark(long shard0, long shard1) { }
    record Fact(long id, String orderNo, String payNo, String status, long version, Instant updatedAt) { }
    record Page(List<Fact> items) { }

    @GetMapping("/internal/rebuild/payments/watermark")
    ApiResponse<Watermark> watermark();

    @GetMapping("/internal/rebuild/payments")
    ApiResponse<Page> page(@RequestParam("shard") int shard, @RequestParam("afterId") long afterId,
            @RequestParam("throughId") long throughId, @RequestParam("limit") int limit);
}
