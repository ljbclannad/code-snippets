package com.lx.governance.order.service;

import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

@LocalTCC
public interface OrderTccAction {

    @TwoPhaseBusinessAction(
            name = "orderTccAction",
            commitMethod = "commit",
            rollbackMethod = "rollback"
    )
    boolean prepare(
            BusinessActionContext actionContext,
            @BusinessActionContextParameter(paramName = "orderNo") String orderNo,
            @BusinessActionContextParameter(paramName = "customerId") String customerId,
            @BusinessActionContextParameter(paramName = "productId") String productId,
            @BusinessActionContextParameter(paramName = "quantity") Integer quantity,
            @BusinessActionContextParameter(paramName = "amountCents") Integer amountCents
    );

    boolean commit(BusinessActionContext actionContext);

    boolean rollback(BusinessActionContext actionContext);
}
