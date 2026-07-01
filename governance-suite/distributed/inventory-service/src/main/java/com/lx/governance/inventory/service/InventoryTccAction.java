package com.lx.governance.inventory.service;

import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.BusinessActionContextParameter;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;

@LocalTCC
public interface InventoryTccAction {

    @TwoPhaseBusinessAction(
            name = "inventoryTccAction",
            commitMethod = "commit",
            rollbackMethod = "rollback"
    )
    boolean prepare(
            BusinessActionContext actionContext,
            @BusinessActionContextParameter(paramName = "orderNo") String orderNo,
            @BusinessActionContextParameter(paramName = "productId") String productId,
            @BusinessActionContextParameter(paramName = "quantity") Integer quantity
    );

    boolean commit(BusinessActionContext actionContext);

    boolean rollback(BusinessActionContext actionContext);
}
