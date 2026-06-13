package com.lx.governance.appointment.config;

import com.lx.governance.common.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SnowflakeConfig {

    @Value("${snowflake.worker-id:5}")
    private long workerId;

    @Value("${snowflake.datacenter-id:1}")
    private long datacenterId;

    @Value("${snowflake.max-backward-ms:5000}")
    private long maxBackwardMs;

    @Bean
    public SnowflakeIdGenerator snowflakeIdGenerator() {
        return new SnowflakeIdGenerator(datacenterId, workerId, maxBackwardMs);
    }
}
