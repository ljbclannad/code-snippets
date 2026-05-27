package com.lx.governance.appointment.service;

/**
 * 支付服务接口
 */
public interface PaymentService {

    /**
     * 支付订单
     *
     * @param userId  用户ID
     * @param orderNo 订单号
     */
    void payOrder(Long userId, String orderNo);
}
