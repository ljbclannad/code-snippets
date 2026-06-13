package com.lx.governance.order.config;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 订单服务 Sentinel 规则。
 *
 * 网关只保留通用入口限流；订单写接口的精细化保护下沉到服务内，
 * 直接按真实业务资源做限流，避免把规则绑定到网关路径。
 */
@Configuration
public class OrderSentinelConfig {

    @Value("${app.sentinel.order.create.qps:5}")
    private double createQps;

    @Value("${app.sentinel.order.create-v2.qps:3}")
    private double createV2Qps;

    @PostConstruct
    public void initFlowRules() {
        FlowRule createRule = new FlowRule();
        createRule.setResource("order:create");
        createRule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        createRule.setCount(createQps);

        FlowRule createV2Rule = new FlowRule();
        createV2Rule.setResource("order:create-v2");
        createV2Rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        createV2Rule.setCount(createV2Qps);

        FlowRuleManager.loadRules(List.of(createRule, createV2Rule));
    }
}
