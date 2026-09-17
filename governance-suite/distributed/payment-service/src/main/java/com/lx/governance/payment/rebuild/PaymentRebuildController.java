package com.lx.governance.payment.rebuild;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.trace.Trace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 仅供 order-service 在内部网络读取支付事实，网关禁止转发该路径。 */
@RestController
@RequestMapping("/internal/rebuild/payments")
public class PaymentRebuildController {
    private final PaymentRebuildRepository repository;

    public PaymentRebuildController(PaymentRebuildRepository repository) { this.repository = repository; }

    @GetMapping("/watermark")
    public ApiResponse<PaymentRebuildFacts.Watermark> watermark() {
        return ApiResponse.ok(repository.watermark(), Trace.getOrCreate());
    }

    @GetMapping
    public ApiResponse<PaymentRebuildFacts.Page> page(@RequestParam("shard") int shard,
            @RequestParam("afterId") long afterId, @RequestParam("throughId") long throughId,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        if ((shard != 0 && shard != 1) || afterId < 0 || throughId < afterId || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("invalid rebuild page");
        }
        return ApiResponse.ok(new PaymentRebuildFacts.Page(repository.page(shard, afterId, throughId, limit)), Trace.getOrCreate());
    }
}
