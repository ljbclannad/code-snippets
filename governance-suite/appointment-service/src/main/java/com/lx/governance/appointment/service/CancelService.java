package com.lx.governance.appointment.service;

/**
 * 取消服务接口
 */
public interface CancelService {

    /**
     * 取消订单
     *
     * @param userId  用户ID
     * @param orderNo 订单号
     * @param reason  取消原因（可选）
     */
    void cancelOrder(Long userId, String orderNo, String reason);
}
