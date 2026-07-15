package com.spring.simulation.ioc.context;

import com.spring.simulation.ioc.scope.BeanScope;
import com.spring.simulation.ioc.scope.ApplicationScope;
import com.spring.simulation.ioc.scope.RequestScope;
import com.spring.simulation.ioc.scope.RequestScopeContextHolder;
import com.spring.simulation.ioc.scope.SessionScope;
import com.spring.simulation.ioc.scope.SessionScopeContextHolder;
import com.spring.simulation.ioc.web.HttpServletRequest;
import com.spring.simulation.ioc.web.HttpSession;
import com.spring.simulation.ioc.web.ServletConfig;
import com.spring.simulation.ioc.web.ServletContext;
import com.spring.simulation.ioc.web.ServletRequestContextHolder;

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
    private final ServletContext servletContext = new ServletContext("/");
    private final ServletConfig servletConfig = new ServletConfig("dispatcherServlet", servletContext);

    public AnnotationConfigWebApplicationContext(String... basePackage) {
        super(basePackage);
    }

    /**
     * 注册 Web 上下文专属的 BeanFactory 能力。
     *
     * ServletContext / ServletConfig 是应用级固定对象；请求和会话对象则通过
     * ThreadLocal 在真正发生注入时动态取得，避免 refresh 时错误地缓存某一次请求。
     */
    @Override
    protected void postProcessBeanFactory() {
        super.postProcessBeanFactory();

        System.out.println("  - [Web Container] Registering web-specific scopes:");
        getBeanFactory().registerScope(BeanScope.REQUEST, new RequestScope());
        System.out.println("    * request scope (每个 HTTP 请求一个实例，已注册)");
        getBeanFactory().registerScope(BeanScope.SESSION, new SessionScope());
        System.out.println("    * session scope (每个 HTTP 会话一个实例，已注册)");
        getBeanFactory().registerScope(BeanScope.APPLICATION, new ApplicationScope());
        System.out.println("    * application scope (整个 Web 应用一个实例，已注册)");

        System.out.println("  - [Web Container] Registering web environment dependencies:");
        getBeanFactory().registerResolvableDependency(ServletContext.class, servletContext);
        System.out.println("    * ServletContext (应用级固定依赖，已注册)");
        getBeanFactory().registerResolvableDependency(ServletConfig.class, servletConfig);
        System.out.println("    * ServletConfig (应用级固定依赖，已注册)");
        getBeanFactory().registerResolvableDependencyProvider(
                HttpServletRequest.class, ServletRequestContextHolder::currentRequest);
        System.out.println("    * HttpServletRequest (当前请求动态依赖，已注册)");
        getBeanFactory().registerResolvableDependencyProvider(
                HttpSession.class, ServletRequestContextHolder::currentSession);
        System.out.println("    * HttpSession (当前会话动态依赖，已注册)");
    }

    /**
     * 模拟 Web 容器在 HTTP 请求进入时绑定 request scope。
     */
    public void beginRequest() {
        beginRequest(new HttpServletRequest("/", null));
    }

    /**
     * 在请求处理开始时绑定当前请求，并激活 request/session scope。
     */
    public void beginRequest(HttpServletRequest request) {
        ServletRequestContextHolder.bind(request);
        RequestScopeContextHolder.beginRequest();
        if (request.getSession() != null) {
            beginSession(request.getSession().getId());
        }
    }

    /**
     * 模拟 Web 容器在 HTTP 请求结束时清理 request scope Bean。
     */
    public void endRequest() {
        try {
            RequestScopeContextHolder.endRequest();
        } finally {
            ServletRequestContextHolder.clear();
            SessionScopeContextHolder.endSession();
        }
    }

    /**
     * 模拟 Web 容器依据 sessionId 将当前请求线程关联到 HTTP 会话。
     */
    public void beginSession(String sessionId) {
        SessionScopeContextHolder.beginSession(sessionId);
    }

    /**
     * 模拟当前请求处理完成后解除线程与 HTTP 会话的关联，但保留 Session Bean。
     */
    public void endSession() {
        SessionScopeContextHolder.endSession();
    }

    /**
     * 模拟 HTTP Session 失效，释放该会话缓存的全部 session-scoped Bean。
     */
    public void invalidateSession(String sessionId) {
        SessionScopeContextHolder.invalidateSession(sessionId);
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
