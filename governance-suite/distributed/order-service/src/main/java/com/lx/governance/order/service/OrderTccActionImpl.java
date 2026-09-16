package com.lx.governance.order.service;

import com.lx.governance.order.domain.OrderDto;
import com.lx.governance.order.repo.OrderRepository;
import com.lx.governance.order.repo.OrderTccLogRepository;
import io.seata.rm.tcc.api.BusinessActionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class OrderTccActionImpl implements OrderTccAction {

    private static final String STATE_TRIED = "TRIED";
    private static final String STATE_COMMITTED = "COMMITTED";
    private static final String STATE_ROLLBACKED = "ROLLBACKED";

    private final OrderRepository orderRepository;
    private final OrderTccLogRepository orderTccLogRepository;
    private final OrderFactEventPublisher orderFactEventPublisher;

    public OrderTccActionImpl(OrderRepository orderRepository,
                              OrderTccLogRepository orderTccLogRepository,
                              OrderFactEventPublisher orderFactEventPublisher) {
        this.orderRepository = orderRepository;
        this.orderTccLogRepository = orderTccLogRepository;
        this.orderFactEventPublisher = orderFactEventPublisher;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean prepare(BusinessActionContext actionContext,
                           String orderNo,
                           String customerId,
                           String productId,
                           Integer quantity,
                           Integer amountCents) {
        OrderTccLogRepository.TccLog logRecord = orderTccLogRepository.findForUpdate(orderNo);
        if (logRecord != null) {
            if (STATE_TRIED.equals(logRecord.state()) || STATE_COMMITTED.equals(logRecord.state())) {
                return true;
            }
            if (STATE_ROLLBACKED.equals(logRecord.state())) {
                throw new IllegalStateException("order tcc suspended, rollback marker already exists");
            }
        }

        OrderDto existing = orderRepository.findByOrderNo(orderNo);
        if (existing == null) {
            orderRepository.insert(orderNo, customerId, productId, quantity, amountCents, "TCC_PREPARED");
        } else if (!"TCC_PREPARED".equals(existing.getStatus()) && !"CREATED".equals(existing.getStatus())) {
            throw new IllegalStateException("unexpected order status for TCC try: " + existing.getStatus());
        }

        if (logRecord == null) {
            orderTccLogRepository.insert(orderNo, actionContext.getXid(), STATE_TRIED);
        } else {
            orderTccLogRepository.updateState(orderNo, STATE_TRIED);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean commit(BusinessActionContext actionContext) {
        String orderNo = actionContext.getActionContext("orderNo", String.class);
        OrderTccLogRepository.TccLog logRecord = orderTccLogRepository.findForUpdate(orderNo);
        if (logRecord == null || STATE_COMMITTED.equals(logRecord.state())) {
            return true;
        }
        if (STATE_ROLLBACKED.equals(logRecord.state())) {
            log.warn("Ignore order tcc commit after rollback marker, orderNo={}", orderNo);
            return true;
        }

        if (orderRepository.updateStatus(orderNo, "CREATED") > 0) {
            orderFactEventPublisher.publish(orderRepository.findByOrderNo(orderNo), "order.created");
        }
        orderTccLogRepository.updateState(orderNo, STATE_COMMITTED);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rollback(BusinessActionContext actionContext) {
        String orderNo = actionContext.getActionContext("orderNo", String.class);
        OrderTccLogRepository.TccLog logRecord = orderTccLogRepository.findForUpdate(orderNo);
        if (logRecord == null) {
            orderTccLogRepository.insert(orderNo, actionContext.getXid(), STATE_ROLLBACKED);
            return true;
        }
        if (STATE_ROLLBACKED.equals(logRecord.state())) {
            return true;
        }
        if (STATE_COMMITTED.equals(logRecord.state())) {
            log.warn("Ignore order tcc rollback after commit marker, orderNo={}", orderNo);
            return true;
        }

        if (orderRepository.updateStatus(orderNo, "CANCELLED") > 0) {
            orderFactEventPublisher.publish(orderRepository.findByOrderNo(orderNo), "order.cancelled");
        }
        orderTccLogRepository.updateState(orderNo, STATE_ROLLBACKED);
        return true;
    }
}
