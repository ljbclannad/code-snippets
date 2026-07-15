package com.spring.simulation.ioc;

/**
 * 更贴近 Spring Web 场景的注解配置上下文。
 *
 * 关系上可以理解为：
 * ApplicationContext
 * -> AnnotationConfigApplicationContext
 * -> AnnotationConfigWebApplicationContext
 *
 * Web 版本在通用注解配置能力之上，额外接管：
 * 1. BeanFactory 的 Web 扩展处理
 * 2. refresh 过程中的 Web 容器启动阶段
 */
public class AnnotationConfigWebApplicationContext extends AnnotationConfigApplicationContext {

    public AnnotationConfigWebApplicationContext(String... basePackage) {
        super(basePackage);
    }

    /**
     * 注册 Web 上下文专属的 BeanFactory 能力。
     *
     * 当前为教学日志模拟；后续可在此处接入真实的 Scope 注册表和 Servlet 相关依赖。
     */
    @Override
    protected void postProcessBeanFactory() {
        super.postProcessBeanFactory();

        System.out.println("  - [Web Container] Registering web-specific scopes:");
        getBeanFactory().registerScope(BeanScope.REQUEST, new RequestScope());
        System.out.println("    * request scope (每个 HTTP 请求一个实例，已注册)");
        System.out.println("    * session scope (每个 HTTP 会话一个实例，待实现)");
        System.out.println("    * application scope (整个 Web 应用一个实例，待实现)");

        System.out.println("  - [Web Container] Registering web environment beans:");
        System.out.println("    * ServletContext (Servlet 上下文)");
        System.out.println("    * ServletConfig (Servlet 配置)");
        System.out.println("    * HttpServletRequest (请求对象，request 作用域)");
        System.out.println("    * HttpSession (会话对象，session 作用域)");

        System.out.println("  - [Web Container] Adding web-aware dependency resolvers");
    }

    /**
     * 模拟 Web 容器在 HTTP 请求进入时绑定 request scope。
     */
    public void beginRequest() {
        RequestScopeContextHolder.beginRequest();
    }

    /**
     * 模拟 Web 容器在 HTTP 请求结束时清理 request scope Bean。
     */
    public void endRequest() {
        RequestScopeContextHolder.endRequest();
    }

    @Override
    protected void onRefresh() {
        System.out.println("\n[Phase 9] onRefresh()");
        System.out.println("  - [Web Container] Initializing embedded web server");
        System.out.println("    * Creating DispatcherServlet");
        System.out.println("    * Registering servlet mappings");
        System.out.println("    * Starting embedded Tomcat/Jetty/Undertow (simulated)");
    }
}
