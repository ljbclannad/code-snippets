package com.lx.governance.order.service;

import com.lx.governance.order.domain.OrderProjectionEvent;
import com.lx.governance.order.repo.OrderAdminReadModelRepository;
import org.springframework.stereotype.Service;

@Service
public class OrderAdminProjectionService {
    private final OrderAdminReadModelRepository repository;

    public OrderAdminProjectionService(OrderAdminReadModelRepository repository) {
        this.repository = repository;
    }

    public boolean project(OrderProjectionEvent event) {
        validate(event);
        return repository.upsertOrderIfNewer(event) > 0;
    }

    private void validate(OrderProjectionEvent event) {
        if (event == null || isBlank(event.eventId()) || isBlank(event.orderNo()) || isBlank(event.customerId())
                || isBlank(event.productId()) || isBlank(event.status()) || event.orderVersion() <= 0
                || event.quantity() <= 0 || event.amountCents() < 0 || event.occurredAt() == null) {
            throw new IllegalArgumentException("invalid order projection event");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
