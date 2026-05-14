package com.lx.governance.aichat.config;

import com.lx.governance.aichat.mapper.AiChatHistoryMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import static org.mockito.Mockito.mock;

/**
 * 测试配置类 - 提供 Mock beans
 */
@TestConfiguration
public class TestConfig {

    @Bean
    @Primary
    public AiChatHistoryMapper aiChatHistoryMapper() {
        return mock(AiChatHistoryMapper.class);
    }
}
