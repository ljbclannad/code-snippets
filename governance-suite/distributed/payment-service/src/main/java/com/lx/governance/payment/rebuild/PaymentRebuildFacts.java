package com.lx.governance.payment.rebuild;

import java.time.Instant;
import java.util.List;

public final class PaymentRebuildFacts {
    private PaymentRebuildFacts() { }

    public record Watermark(long shard0, long shard1) { }
    public record Fact(long id, String orderNo, String payNo, String status, long version, Instant updatedAt) { }
    public record Page(List<Fact> items) { }
}
