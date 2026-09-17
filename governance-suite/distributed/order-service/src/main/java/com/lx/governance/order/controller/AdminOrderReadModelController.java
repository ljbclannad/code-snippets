package com.lx.governance.order.controller;

import java.time.Instant;
import java.util.List;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.adminauth.AdminOrderSearchItem;
import com.lx.governance.order.adminauth.AdminOrderSearchViewPolicy;
import com.lx.governance.order.adminauth.InventorySnapshotEnricher;
import com.lx.governance.order.adminauth.AdminSearchScope;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;
import com.lx.governance.order.repo.AdminOrderReadModelQueryRepository;
import com.lx.governance.order.observability.AdminOrderActivity;
import com.lx.governance.order.search.OrderOperatorOrder;
import com.lx.governance.order.search.OrderOperatorSearchCriteria;
import com.lx.governance.order.search.OrderOperatorSearchCursor;
import com.lx.governance.order.search.OrderOperatorSearchCursorCodec;
import com.lx.governance.order.search.OrderOperatorSearchPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/orders")
public class AdminOrderReadModelController {
    private final AdminSearchScopeResolver searchScopeResolver;
    private final AdminOrderReadModelQueryRepository repository;
    private final OrderOperatorSearchCursorCodec cursorCodec;
    private final AdminOrderSearchViewPolicy viewPolicy;
    private final InventorySnapshotEnricher inventorySnapshotEnricher;
    private final AdminOrderActivity activity;

    public AdminOrderReadModelController(AdminSearchScopeResolver searchScopeResolver,
            AdminOrderReadModelQueryRepository repository, OrderOperatorSearchCursorCodec cursorCodec,
            AdminOrderSearchViewPolicy viewPolicy, InventorySnapshotEnricher inventorySnapshotEnricher,
            AdminOrderActivity activity) {
        this.searchScopeResolver = searchScopeResolver;
        this.repository = repository;
        this.cursorCodec = cursorCodec;
        this.viewPolicy = viewPolicy;
        this.inventorySnapshotEnricher = inventorySnapshotEnricher;
        this.activity = activity;
    }

    @GetMapping
    public ApiResponse<OrderOperatorSearchPage> list(
            @RequestHeader(value = AuthHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) String orderNo,
            @RequestParam(required = false) String customerSearchToken,
            @RequestParam(required = false) String productId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(required = false) Long minAmountCents,
            @RequestParam(required = false) Long maxAmountCents,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        AdminSearchScope scope = searchScopeResolver.resolve(authorization);
        OrderOperatorSearchCriteria criteria = OrderOperatorSearchCriteria.create(orderNo, customerSearchToken, productId,
                status, paymentStatus, createdFrom, createdTo, minAmountCents, maxAmountCents, sort, direction, limit);
        viewPolicy.validateSearch(scope.roles(), criteria.sort().name(), minAmountCents, maxAmountCents, paymentStatus);
        OrderOperatorSearchCursor decodedCursor = cursor == null || cursor.isBlank() ? null : cursorCodec.decode(cursor, criteria);
        List<OrderOperatorOrder> results = repository.find(criteria, decodedCursor);
        boolean hasMore = results.size() > criteria.limit();
        List<OrderOperatorOrder> items = hasMore ? results.subList(0, criteria.limit()) : results;
        String nextCursor = hasMore ? cursorCodec.encode(new OrderOperatorSearchCursor(sortValue(items.get(items.size() - 1), criteria),
                items.get(items.size() - 1).orderNo()), criteria) : null;
        List<AdminOrderSearchItem> visibleItems = items.stream().map(item -> viewPolicy.view(scope.roles(), item)).toList();
        if (viewPolicy.isInventoryView(scope.roles())) visibleItems = inventorySnapshotEnricher.enrich(visibleItems);
        activity.record("SEARCH", scope.userId(), criteria.fingerprint(), "SUCCESS", "rows=" + visibleItems.size());
        return ApiResponse.ok(new OrderOperatorSearchPage(visibleItems, nextCursor), Trace.getOrCreate());
    }

    private String sortValue(OrderOperatorOrder item, OrderOperatorSearchCriteria criteria) {
        return switch (criteria.sort()) {
            case CREATED_AT -> item.createdAt().toString();
            case AMOUNT_CENTS -> String.valueOf(item.amountCents());
            case PAYMENT_UPDATED_AT -> item.paymentUpdatedAt().toString();
        };
    }
}
