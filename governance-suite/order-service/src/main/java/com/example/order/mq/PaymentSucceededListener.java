package com.example.order.mq;

import com.example.order.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentSucceededListener {
    private static final Logger log = LoggerFactory.getLogger(PaymentSucceededListener.class);
    private final OrderMapper orderMapper;

    public PaymentSucceededListener(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    @RabbitListener(queues = "q.payment.succeeded")
    public void onMessage(String json) {
        // 简化：{"orderNo":"..."}
        String orderNo = extract(json, "orderNo");
        if (orderNo != null) {
            orderMapper.updateStatus(orderNo, "PAID");
            log.info("order status updated to PAID, orderNo={}", orderNo);
        }
    }

    private String extract(String json, String key) {
        String p = "\"" + key + "\":\"";
        int i = json.indexOf(p);
        if (i < 0) return null;
        int s = i + p.length();
        int e = json.indexOf("\"", s);
        if (e < 0) return null;
        return json.substring(s, e);
    }
}
