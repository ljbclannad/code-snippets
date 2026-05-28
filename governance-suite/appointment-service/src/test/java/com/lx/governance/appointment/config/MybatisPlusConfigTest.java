package com.lx.governance.appointment.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MybatisPlusConfigTest {

    @Test
    void mybatisPlusInterceptor_registersOptimisticLocker() {
        MybatisPlusConfig config = new MybatisPlusConfig();

        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();

        assertNotNull(interceptor);
        assertTrue(
            interceptor.getInterceptors().stream().anyMatch(OptimisticLockerInnerInterceptor.class::isInstance),
            "OptimisticLockerInnerInterceptor should be registered"
        );
    }
}
