package com.spring.simulation.examples.context;

import com.spring.simulation.demo.web.WebEndpoint;
import com.spring.simulation.ioc.context.AnnotationConfigWebApplicationContext;
import com.spring.simulation.ioc.web.HttpServletRequest;
import com.spring.simulation.ioc.web.HttpSession;

/**
 * 验证 singleton 注入 scoped proxy：请求标识随请求变化，应用标识保持不变。
 */
public class ScopedProxyDemo {
    public static void main(String[] args) throws Exception {
        AnnotationConfigWebApplicationContext context =
                new AnnotationConfigWebApplicationContext("com.spring.simulation.demo.web");
        context.refresh();
        WebEndpoint endpoint = context.getBeanFactory().getBeanByType(WebEndpoint.class);

        context.beginRequest(new HttpServletRequest("/first", new HttpSession("session-1")));
        String first = endpoint.describeScopeIdentities();
        context.endRequest();

        context.beginRequest(new HttpServletRequest("/second", new HttpSession("session-2")));
        String second = endpoint.describeScopeIdentities();
        context.endRequest();

        System.out.println("First request:  " + first);
        System.out.println("Second request: " + second);
    }
}
