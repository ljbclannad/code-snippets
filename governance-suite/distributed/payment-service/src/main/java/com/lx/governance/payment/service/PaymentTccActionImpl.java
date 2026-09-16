package com.lx.governance.payment.service;

import com.lx.governance.common.id.IdGenerator;
import com.lx.governance.payment.domain.PaymentDto;
import com.lx.governance.payment.repo.PaymentRepository;
import com.lx.governance.payment.repo.PaymentTccLogRepository;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentTccActionImpl implements PaymentTccAction {

    private static final String STATE_TRIED = "TRIED";
    private static final String STATE_COMMITTED = "COMMITTED";
    private static final String STATE_ROLLBACKED = "ROLLBACKED";
    private static final Logger log = LoggerFactory.getLogger(PaymentTccActionImpl.class);

    private final PaymentRepository paymentRepository;
    private final PaymentTccLogRepository paymentTccLogRepository;
    private final PaymentFactEventPublisher paymentFactEventPublisher;
    private final IdGenerator payNoGenerator;

    public PaymentTccActionImpl(PaymentRepository paymentRepository,
                                 PaymentTccLogRepository paymentTccLogRepository,
                                 PaymentFactEventPublisher paymentFactEventPublisher,
                                 IdGenerator payNoSegmentGenerator) {
        this.paymentRepository = paymentRepository;
        this.paymentTccLogRepository = paymentTccLogRepository;
        this.paymentFactEventPublisher = paymentFactEventPublisher;
        this.payNoGenerator = payNoSegmentGenerator;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean prepare(BusinessActionContext actionContext, String orderNo, Long userId, Integer amountCents) {
        PaymentTccLogRepository.TccLog logRecord = paymentTccLogRepository.findForUpdate(orderNo);
        if (logRecord != null) {
            if (STATE_TRIED.equals(logRecord.state()) || STATE_COMMITTED.equals(logRecord.state())) {
                return true;
            }
            if (STATE_ROLLBACKED.equals(logRecord.state())) {
                throw new IllegalStateException("payment tcc suspended, rollback marker already exists");
            }
        }

        PaymentDto existing = paymentRepository.findByOrderNo(orderNo);
        if (existing == null) {
            paymentRepository.insert(userId, "P" + payNoGenerator.nextId(), orderNo, amountCents, "PREPARED", "tcc:" + orderNo);
            paymentFactEventPublisher.publish(paymentRepository.findByOrderNo(orderNo));
        } else if (!"PREPARED".equals(existing.getStatus()) && !"SUCCEEDED".equals(existing.getStatus())) {
            throw new IllegalStateException("unexpected payment status for TCC try: " + existing.getStatus());
        }

        if (logRecord == null) {
            paymentTccLogRepository.insert(orderNo, actionContext.getXid(), STATE_TRIED);
        } else {
            paymentTccLogRepository.updateState(orderNo, STATE_TRIED);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean commit(BusinessActionContext actionContext) {
        String orderNo = actionContext.getActionContext("orderNo", String.class);
        PaymentTccLogRepository.TccLog logRecord = paymentTccLogRepository.findForUpdate(orderNo);
        if (logRecord == null || STATE_COMMITTED.equals(logRecord.state())) {
            return true;
        }
        if (STATE_ROLLBACKED.equals(logRecord.state())) {
            log.warn("Ignore payment tcc commit after rollback marker, orderNo={}", orderNo);
            return true;
        }

        if (paymentRepository.updateStatusByOrderNo(orderNo, "SUCCEEDED") > 0) {
            paymentFactEventPublisher.publish(paymentRepository.findByOrderNo(orderNo));
        }
        paymentTccLogRepository.updateState(orderNo, STATE_COMMITTED);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rollback(BusinessActionContext actionContext) {
        String orderNo = actionContext.getActionContext("orderNo", String.class);
        PaymentTccLogRepository.TccLog logRecord = paymentTccLogRepository.findForUpdate(orderNo);
        if (logRecord == null) {
            paymentTccLogRepository.insert(orderNo, actionContext.getXid(), STATE_ROLLBACKED);
            return true;
        }
        if (STATE_ROLLBACKED.equals(logRecord.state())) {
            return true;
        }
        if (STATE_COMMITTED.equals(logRecord.state())) {
            log.warn("Ignore payment tcc rollback after commit marker, orderNo={}", orderNo);
            return true;
        }

        if (paymentRepository.updateStatusByOrderNo(orderNo, "CANCELLED") > 0) {
            paymentFactEventPublisher.publish(paymentRepository.findByOrderNo(orderNo));
        }
        paymentTccLogRepository.updateState(orderNo, STATE_ROLLBACKED);
        return true;
    }
}
