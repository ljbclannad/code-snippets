package com.lx.governance.order.adminauth;

import java.util.Set;

import com.lx.governance.common.auth.AdminRole;
import com.lx.governance.order.search.OrderOperatorOrder;
import org.springframework.stereotype.Component;

@Component
public class AdminOrderSearchViewPolicy {
    public AdminOrderSearchItem view(Set<String> roles, OrderOperatorOrder order) {
        SearchView searchView = resolve(roles);
        String customerId = searchView == SearchView.FINANCE ? order.customerId() : maskCustomerId(order.customerId());
        if (searchView == SearchView.INVENTORY) {
            return new AdminOrderSearchItem(order.orderNo(), customerId, order.productId(), order.quantity(), null,
                    order.status(), null, null, null, order.createdAt());
        }
        return new AdminOrderSearchItem(order.orderNo(), customerId, order.productId(), order.quantity(), order.amountCents(),
                order.status(), order.paymentStatus(), order.payNo(),
                order.paymentUpdatedAt(), order.createdAt());
    }

    public void validateSearch(Set<String> roles, String sort, Long minAmountCents, Long maxAmountCents, String paymentStatus) {
        if (resolve(roles) != SearchView.INVENTORY) return;
        if (minAmountCents != null || maxAmountCents != null || paymentStatus != null || "AMOUNT_CENTS".equals(sort)
                || "PAYMENT_UPDATED_AT".equals(sort)) {
            throw new IllegalArgumentException("inventory administrators cannot filter or sort by financial fields");
        }
    }

    private SearchView resolve(Set<String> roles) {
        if (roles.contains(AdminRole.FINANCE_VIEWER.name())) return SearchView.FINANCE;
        if (roles.contains(AdminRole.ORDER_OPERATOR.name())) return SearchView.OPERATOR;
        return SearchView.INVENTORY;
    }

    private String maskCustomerId(String customerId) {
        if (customerId == null || customerId.isBlank()) return customerId;
        if (customerId.length() <= 2) return "***";
        return String.valueOf(customerId.charAt(0)) + "***" + customerId.charAt(customerId.length() - 1);
    }

    private enum SearchView {
        FINANCE,
        OPERATOR,
        INVENTORY
    }
}
