package com.lx.governance.order.search;

import java.time.Instant;

public record OrderOperatorSearchCursor(Instant createdAt, String orderNo) {
}
