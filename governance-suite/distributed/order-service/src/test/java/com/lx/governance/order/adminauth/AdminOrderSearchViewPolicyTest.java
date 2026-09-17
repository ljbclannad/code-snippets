package com.lx.governance.order.adminauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;

import com.lx.governance.order.search.OrderOperatorOrder;
import org.junit.jupiter.api.Test;

class AdminOrderSearchViewPolicyTest {
    private final AdminOrderSearchViewPolicy policy = new AdminOrderSearchViewPolicy();
    private final OrderOperatorOrder order = new OrderOperatorOrder("O-1", "customer-123456", "token-1", "P-1", 2,
            100L, "CREATED", "PREPARED", "PAY-1", Instant.parse("2026-09-15T11:00:00Z"),
            Instant.parse("2026-09-15T10:00:00Z"));

    @Test
    void returnsCompleteFinancialView() {
        AdminOrderSearchItem item = policy.view(Set.of("FINANCE_VIEWER"), order);

        assertThat(item.customerId()).isEqualTo("customer-123456");
        assertThat(item.amountCents()).isEqualTo(100L);
        assertThat(item.paymentStatus()).isEqualTo("PREPARED");
        assertThat(item.payNo()).isEqualTo("PAY-1");
    }

    @Test
    void masksCustomerIdentifierForOperatorAndKeepsOperationalFields() {
        AdminOrderSearchItem item = policy.view(Set.of("ORDER_OPERATOR"), order);

        assertThat(item.customerId()).isNotEqualTo("customer-123456");
        assertThat(item.customerId()).contains("***");
        assertThat(item.amountCents()).isEqualTo(100L);
        assertThat(item.paymentStatus()).isEqualTo("PREPARED");
    }

    @Test
    void excludesFinancialFieldsForInventoryView() {
        AdminOrderSearchItem item = policy.view(Set.of("INVENTORY_VIEWER"), order);

        assertThat(item.customerId()).contains("***");
        assertThat(item.amountCents()).isNull();
        assertThat(item.paymentStatus()).isNull();
        assertThat(item.payNo()).isNull();
        assertThat(item.paymentUpdatedAt()).isNull();
    }

    @Test
    void choosesHighestPermissionForMultipleRoles() {
        AdminOrderSearchItem financeItem = policy.view(Set.of("FINANCE_VIEWER", "INVENTORY_VIEWER"), order);
        AdminOrderSearchItem operatorItem = policy.view(Set.of("ORDER_OPERATOR", "INVENTORY_VIEWER"), order);

        assertThat(financeItem.customerId()).isEqualTo("customer-123456");
        assertThat(operatorItem.amountCents()).isEqualTo(100L);
    }

    @Test
    void rejectsSensitiveInventorySearchCapabilities() {
        assertThatThrownBy(() -> policy.validateSearch(Set.of("INVENTORY_VIEWER"), "AMOUNT_CENTS", null, null,
                "PREPARED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("inventory administrators cannot filter or sort by financial fields");
    }
}
