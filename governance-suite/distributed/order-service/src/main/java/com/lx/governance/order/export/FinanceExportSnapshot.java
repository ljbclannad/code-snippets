package com.lx.governance.order.export;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

import com.lx.governance.order.adminauth.AdminSearchScope;
import com.lx.governance.order.search.OrderOperatorSearchCriteria;

/**
 * 导出任务创建时持久化的、不可随请求后续变化而改变的检索上下文。
 */
public record FinanceExportSnapshot(String criteria, String fieldSet, String roleSnapshot, String searchScope,
        Instant readModelWatermark) {
    private static final List<String> FINANCE_FIELDS = List.of("orderNo", "customerId", "productId", "quantity",
            "amountCents", "status", "paymentStatus", "payNo", "paymentUpdatedAt", "createdAt");

    public static FinanceExportSnapshot from(AdminSearchScope scope, OrderOperatorSearchCriteria criteria,
            Instant readModelWatermark) {
        return new FinanceExportSnapshot(criteriaSnapshot(criteria), String.join(",", FINANCE_FIELDS),
                String.join(",", new TreeSet<>(scope.roles())),
                "userId=" + scope.userId() + "\nusername=" + escape(scope.username()), readModelWatermark);
    }

    public static List<String> financeFields() {
        return FINANCE_FIELDS;
    }

    public static List<String> parseFinanceFields(String fieldSet) {
        if (fieldSet == null || fieldSet.isBlank()) throw new IllegalArgumentException("missing frozen finance field set");
        List<String> fields = new ArrayList<>(List.of(fieldSet.split(",", -1)));
        if (fields.isEmpty() || !FINANCE_FIELDS.containsAll(fields) || fields.size() != new HashSet<>(fields).size()) {
            throw new IllegalArgumentException("invalid frozen finance field set");
        }
        return fields;
    }

    private static String criteriaSnapshot(OrderOperatorSearchCriteria criteria) {
        return "orderNo=" + escape(criteria.orderNo()) + "\ncustomerSearchToken=" + escape(criteria.customerSearchToken())
                + "\nproductId=" + escape(criteria.productId()) + "\nstatus=" + escape(criteria.status())
                + "\npaymentStatus=" + escape(criteria.paymentStatus()) + "\ncreatedFrom=" + criteria.createdFrom()
                + "\ncreatedTo=" + criteria.createdTo() + "\nminAmountCents=" + criteria.minAmountCents()
                + "\nmaxAmountCents=" + criteria.maxAmountCents() + "\nsort=" + criteria.sort()
                + "\ndirection=" + criteria.direction();
    }

    private static String escape(String value) {
        return value == null ? "<null>" : value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }
}
