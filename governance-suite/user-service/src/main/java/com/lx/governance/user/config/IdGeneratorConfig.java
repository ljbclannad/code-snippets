package com.lx.governance.user.config;

import com.lx.governance.common.id.IdSegmentRepository;
import com.lx.governance.common.id.SegmentIdGenerator;
import com.lx.governance.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ID 生成器配置。
 */
@Configuration
public class IdGeneratorConfig {

    @Bean
    public SegmentIdGenerator userIdSegmentGenerator(IdSegmentRepository repository) {
        return new SegmentIdGenerator("user_id", repository);
    }

    @Bean
    public SnowflakeIdGenerator userIdSnowflakeGenerator(
            @Value("${id.snowflake.worker-id}") long workerId,
            @Value("${id.snowflake.max-backward-ms}") long maxBackwardMs) {
        return new SnowflakeIdGenerator(workerId, maxBackwardMs);
    }
}
