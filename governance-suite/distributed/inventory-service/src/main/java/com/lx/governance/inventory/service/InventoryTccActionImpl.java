package com.lx.governance.inventory.service;

import com.lx.governance.inventory.repo.InventoryTccLogRepository;
import com.lx.governance.inventory.repo.StockRepository;
import io.seata.rm.tcc.api.BusinessActionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryTccActionImpl implements InventoryTccAction {

    private static final String STATE_TRIED = "TRIED";
    private static final String STATE_COMMITTED = "COMMITTED";
    private static final String STATE_ROLLBACKED = "ROLLBACKED";
    private static final Logger log = LoggerFactory.getLogger(InventoryTccActionImpl.class);

    private final StockRepository stockRepository;
    private final InventoryTccLogRepository inventoryTccLogRepository;

    public InventoryTccActionImpl(StockRepository stockRepository,
                                  InventoryTccLogRepository inventoryTccLogRepository) {
        this.stockRepository = stockRepository;
        this.inventoryTccLogRepository = inventoryTccLogRepository;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean prepare(BusinessActionContext actionContext, String orderNo, String productId, Integer quantity) {
        InventoryTccLogRepository.TccLog logRecord = inventoryTccLogRepository.findForUpdate(orderNo);
        if (logRecord != null) {
            if (STATE_TRIED.equals(logRecord.state()) || STATE_COMMITTED.equals(logRecord.state())) {
                return true;
            }
            if (STATE_ROLLBACKED.equals(logRecord.state())) {
                throw new IllegalStateException("inventory tcc suspended, rollback marker already exists");
            }
        }

        StockRepository.StockRow row = stockRepository.find(productId);
        if (row == null) {
            throw new IllegalArgumentException("product not found");
        }
        if (row.available < quantity) {
            throw new IllegalArgumentException("insufficient stock");
        }

        int updated = stockRepository.deductWithVersion(productId, quantity, row.version);
        if (updated <= 0) {
            throw new IllegalStateException("stock busy, retry later");
        }

        if (logRecord == null) {
            inventoryTccLogRepository.insert(orderNo, productId, quantity, actionContext.getXid(), STATE_TRIED);
        } else {
            inventoryTccLogRepository.updateState(orderNo, STATE_TRIED);
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean commit(BusinessActionContext actionContext) {
        String orderNo = actionContext.getActionContext("orderNo", String.class);
        InventoryTccLogRepository.TccLog logRecord = inventoryTccLogRepository.findForUpdate(orderNo);
        if (logRecord == null || STATE_COMMITTED.equals(logRecord.state())) {
            return true;
        }
        if (STATE_ROLLBACKED.equals(logRecord.state())) {
            log.warn("Ignore inventory tcc commit after rollback marker, orderNo={}", orderNo);
            return true;
        }

        inventoryTccLogRepository.updateState(orderNo, STATE_COMMITTED);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rollback(BusinessActionContext actionContext) {
        String orderNo = actionContext.getActionContext("orderNo", String.class);
        String productId = actionContext.getActionContext("productId", String.class);
        Integer quantity = actionContext.getActionContext("quantity", Integer.class);

        InventoryTccLogRepository.TccLog logRecord = inventoryTccLogRepository.findForUpdate(orderNo);
        if (logRecord == null) {
            inventoryTccLogRepository.insert(orderNo, productId, quantity, actionContext.getXid(), STATE_ROLLBACKED);
            return true;
        }
        if (STATE_ROLLBACKED.equals(logRecord.state())) {
            return true;
        }
        if (STATE_COMMITTED.equals(logRecord.state())) {
            log.warn("Ignore inventory tcc rollback after commit marker, orderNo={}", orderNo);
            return true;
        }

        StockRepository.StockRow row = stockRepository.find(logRecord.productId());
        if (row == null) {
            throw new IllegalArgumentException("product not found");
        }
        stockRepository.increase(logRecord.productId(), logRecord.quantity());
        inventoryTccLogRepository.updateState(orderNo, STATE_ROLLBACKED);
        return true;
    }
}
