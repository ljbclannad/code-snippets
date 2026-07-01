package com.lx.governance.payment.service;

import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

@LocalTCC
public interface PaymentTccAction {

    @TwoPhaseBusinessAction(
            name = "paymentTccAction",
            commitMethod = "commit",
            rollbackMethod = "rollback"
    )
    boolean prepare(
            BusinessActionContext actionContext,
            @BusinessActionContextParameter(paramName = "orderNo") String orderNo,
            @BusinessActionContextParameter(paramName = "userId") Long userId,
            @BusinessActionContextParameter(paramName = "amountCents") Integer amountCents
    );

    boolean commit(BusinessActionContext actionContext);

    boolean rollback(BusinessActionContext actionContext);
}
