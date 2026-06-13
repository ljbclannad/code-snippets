package com.lx.governance.order.id;

import com.lx.governance.common.id.IdGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderNoGenerator {

    private final IdGenerator generator;

    public OrderNoGenerator(
            @Value("${id.mode:segment}") String mode,
            @Qualifier("orderNoSegmentGenerator") IdGenerator segmentGenerator,
            @Qualifier("orderNoSnowflakeGenerator") IdGenerator snowflakeGenerator) {
        this.generator = "snowflake".equalsIgnoreCase(mode) ? snowflakeGenerator : segmentGenerator;
    }

    public String nextOrderNo() {
        return "O" + generator.nextId();
    }

    public String nextSeckillOrderNo() {
        return "S" + generator.nextId();
    }
}
