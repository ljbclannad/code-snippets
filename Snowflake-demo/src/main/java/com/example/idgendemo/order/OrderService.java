package com.example.idgendemo.order;

import com.example.idgendemo.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class OrderService {

    private final IdGenerator idGenerator;
    private final OrderMapper orderMapper;

    public OrderService(IdGenerator idGenerator, OrderMapper orderMapper) {
        this.idGenerator = idGenerator;
        this.orderMapper = orderMapper;
    }

    @Transactional
    public Order createOrder(long userId, java.math.BigDecimal amount) {
        // grayKey 用 userId：灰度稳定，不会“同一个用户一会儿雪花一会儿号段”
        long id = idGenerator.nextId(String.valueOf(userId));

        Order o = new Order();
        o.setId(id);
        o.setUserId(userId);
        o.setAmount(amount);
        o.setStatus("CREATED");
        o.setCreateTime(LocalDateTime.now());

        orderMapper.insert(o);
        return o;
    }

    public Order findById(long id) {
        return orderMapper.selectById(id);
    }
}
