package com.lx.governance.order.controller;

import java.time.Instant;
import java.util.List;

import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.common.auth.AuthHeaders;
import com.lx.governance.common.auth.AdminRole;
import com.lx.governance.common.trace.Trace;
import com.lx.governance.order.adminauth.AdminSearchScopeResolver;
import com.lx.governance.order.adminauth.OrderOperatorRequiredException;
import com.lx.governance.order.repo.AdminOrderReadModelQueryRepository;
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

    public AdminOrderReadModelController(AdminSearchScopeResolver searchScopeResolver,
            AdminOrderReadModelQueryRepository repository, OrderOperatorSearchCursorCodec cursorCodec) {
        this.searchScopeResolver = searchScopeResolver;
        this.repository = repository;
        this.cursorCodec = cursorCodec;
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
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        if (!searchScopeResolver.resolve(authorization).roles().contains(AdminRole.ORDER_OPERATOR.name())) {
            throw new OrderOperatorRequiredException();
        }
        OrderOperatorSearchCriteria criteria = OrderOperatorSearchCriteria.create(orderNo, customerSearchToken, productId,
                status, paymentStatus, createdFrom, createdTo, minAmountCents, maxAmountCents, limit);
        OrderOperatorSearchCursor decodedCursor = cursor == null || cursor.isBlank() ? null : cursorCodec.decode(cursor, criteria);
        List<OrderOperatorOrder> results = repository.find(criteria, decodedCursor);
        boolean hasMore = results.size() > criteria.limit();
        List<OrderOperatorOrder> items = hasMore ? results.subList(0, criteria.limit()) : results;
        String nextCursor = hasMore ? cursorCodec.encode(new OrderOperatorSearchCursor(items.get(items.size() - 1).createdAt(),
                items.get(items.size() - 1).orderNo()), criteria) : null;
        return ApiResponse.ok(new OrderOperatorSearchPage(items, nextCursor), Trace.getOrCreate());
    }
}
