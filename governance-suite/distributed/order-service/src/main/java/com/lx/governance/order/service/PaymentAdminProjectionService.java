package com.lx.governance.order.service;

import com.lx.governance.order.domain.PaymentProjectionEvent;
import com.lx.governance.order.repo.OrderAdminReadModelRepository;
import org.springframework.stereotype.Service;

@Service
public class PaymentAdminProjectionService {
    private final OrderAdminReadModelRepository repository;

    public PaymentAdminProjectionService(OrderAdminReadModelRepository repository) {
        this.repository = repository;
    }

    public boolean project(PaymentProjectionEvent event) {
        validate(event);
        return repository.upsertPaymentIfNewer(event) > 0;
    }

    private void validate(PaymentProjectionEvent event) {
        if (event == null || isBlank(event.eventId()) || isBlank(event.orderNo()) || isBlank(event.payNo())
                || isBlank(event.paymentStatus()) || event.paymentVersion() <= 0 || event.paymentUpdatedAt() == null
                || event.occurredAt() == null) {
            throw new IllegalArgumentException("invalid payment projection event");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
