package com.lx.governance.order.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderNoGenerator {

    private final SnowflakeIdGenerator generator;

    public OrderNoGenerator(@Value("${id.snowflake.worker-id:1}") long workerId,
                            @Value("${id.snowflake.max-backward-ms:5}") long maxBackwardMs) {
        this.generator = new SnowflakeIdGenerator(workerId, maxBackwardMs);
    }

    public String nextOrderNo() {
        return "O" + generator.nextId();
    }

    public String nextSeckillOrderNo() {
        return "S" + generator.nextId();
    }
}
