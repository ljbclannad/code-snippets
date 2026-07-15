package com.spring.simulation.demo.web;

import com.spring.simulation.ioc.annotation.Autowired;
import com.spring.simulation.ioc.annotation.Component;

/**
 * singleton Bean 注入两个 Web Scope 接口，用于演示 scoped proxy 的延迟查找行为。
 */
@Component
public class WebEndpoint {
    @Autowired
    private RequestIdentity requestIdentity;

    @Autowired
    private ApplicationIdentity applicationIdentity;

    public String describeScopeIdentities() {
        return requestIdentity.getValue() + ", " + applicationIdentity.getValue();
    }
}
