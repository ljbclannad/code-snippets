package com.lx.governance.order.search;

import java.util.List;

import com.lx.governance.order.adminauth.AdminOrderSearchItem;

public record OrderOperatorSearchPage(List<AdminOrderSearchItem> items, String nextCursor) {
}
