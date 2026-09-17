package com.lx.governance.order.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;

import com.lx.governance.order.adminauth.AdminSearchScope;
import com.lx.governance.order.search.OrderOperatorSearchCriteria;
import org.junit.jupiter.api.Test;

class FinanceExportSnapshotTest {
    @Test
    void freezesFinanceFieldsRolesScopeCriteriaAndReadModelWatermark() {
        OrderOperatorSearchCriteria criteria = OrderOperatorSearchCriteria.create("O-1", "masked", "P-1", "PAID",
                "SUCCESS", Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"), 100L, 200L,
                "amount_cents", "asc", 100);

        FinanceExportSnapshot snapshot = FinanceExportSnapshot.from(
                new AdminSearchScope(42L, "finance\nadmin", Set.of("ORDER_OPERATOR", "FINANCE_VIEWER")), criteria,
                Instant.parse("2026-09-02T01:00:00Z"));

        assertThat(snapshot.fieldSet()).isEqualTo("orderNo,customerId,productId,quantity,amountCents,status,paymentStatus,payNo,paymentUpdatedAt,createdAt");
        assertThat(snapshot.roleSnapshot()).isEqualTo("FINANCE_VIEWER,ORDER_OPERATOR");
        assertThat(snapshot.searchScope()).isEqualTo("userId=42\nusername=finance\\nadmin");
        assertThat(snapshot.criteria()).contains("orderNo=O-1", "sort=AMOUNT_CENTS", "direction=ASC");
        assertThat(snapshot.readModelWatermark()).isEqualTo(Instant.parse("2026-09-02T01:00:00Z"));
    }
}
