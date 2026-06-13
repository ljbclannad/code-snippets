package com.lx.governance.order.config;

import com.lx.governance.common.id.IdSegmentRepository;
import com.lx.governance.common.id.SegmentIdGenerator;
import com.lx.governance.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ID 生成器配置。
 * 提供号段模式和 Snowflake 两种 ID 生成器。
 */
@Configuration
public class IdGeneratorConfig {

    @Bean
    public SegmentIdGenerator orderNoSegmentGenerator(IdSegmentRepository repository) {
        return new SegmentIdGenerator("order_no", repository);
    }

    @Bean
    public SnowflakeIdGenerator orderNoSnowflakeGenerator(
            @Value("${id.snowflake.worker-id}") long workerId,
            @Value("${id.snowflake.max-backward-ms}") long maxBackwardMs) {
        return new SnowflakeIdGenerator(workerId, maxBackwardMs);
    }
}
