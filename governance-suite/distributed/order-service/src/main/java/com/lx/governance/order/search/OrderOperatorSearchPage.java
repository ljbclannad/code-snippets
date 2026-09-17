package com.lx.governance.order.search;

import java.util.List;

public record OrderOperatorSearchPage(List<OrderOperatorOrder> items, String nextCursor) {
}
