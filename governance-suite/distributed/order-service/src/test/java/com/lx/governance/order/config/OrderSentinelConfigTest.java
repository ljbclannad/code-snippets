package com.lx.governance.order.config;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OrderSentinelConfigTest {

    @AfterEach
    void tearDown() {
        FlowRuleManager.loadRules(List.of());
    }

    @Test
    void shouldLoadFlowRulesForOrderWriteResources() {
        OrderSentinelConfig config = new OrderSentinelConfig();
        ReflectionTestUtils.setField(config, "createQps", 6);
        ReflectionTestUtils.setField(config, "createV2Qps", 3);

        config.initFlowRules();

        FlowRule createRule = FlowRuleManager.getRules()
            .stream()
            .filter(rule -> "order:create".equals(rule.getResource()))
            .findFirst()
            .orElse(null);
        FlowRule createV2Rule = FlowRuleManager.getRules()
            .stream()
            .filter(rule -> "order:create-v2".equals(rule.getResource()))
            .findFirst()
            .orElse(null);

        assertNotNull(createRule);
        assertNotNull(createV2Rule);
        assertEquals(6d, createRule.getCount());
        assertEquals(3d, createV2Rule.getCount());
    }
}
