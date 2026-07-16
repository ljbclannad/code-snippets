package com.spring.simulation.ioc.context;

import java.util.ArrayList;
import java.util.List;

import com.spring.simulation.environment.Environment;
import com.spring.simulation.ioc.aware.ApplicationContextAwareProcessor;
import com.spring.simulation.ioc.factory.BeanDefinition;
import com.spring.simulation.ioc.factory.BeanFactory;
import com.spring.simulation.ioc.factory.BeanFactoryPostProcessor;
import com.spring.simulation.ioc.factory.BeanPostProcessor;

public class ApplicationContext {
    // 在这里创建的目的是保证：当前 Context 内，按 Bean 名称从容器取环境对象，身份完全一致。
    private final Environment environment = new Environment();
    private BeanFactory beanFactory = new BeanFactory();
    private List<BeanPostProcessor> beanPostProcessors = new ArrayList<>();
    private List<BeanFactoryPostProcessor> beanFactoryPostProcessors = new ArrayList<>();
    private List<String> registeredBeanNames = new ArrayList<>();

    /**
     * Spring 容器初始化的核心方法：refresh()
     *
     * 为什么要分 12 个阶段？
     * 1. 职责分离：每个阶段负责特定的初始化任务，便于理解和维护
     * 2. 扩展性：子类可以重写特定阶段来定制行为（模板方法模式）
     * 3. 有序性：确保初始化步骤按正确顺序执行
     * 4. 可控性：每个阶段都是明确的检查点，便于调试和监控
     *
     * 这 12 个阶段是 Spring 框架设计的精髓，理解它们就理解了 Spring 的启动流程
     */
    public void refresh() throws Exception {
        System.out.println("\n========== Spring Container Initialization Started ==========");
        System.out.println("Simulating org.springframework.context.support.AbstractApplicationContext.refresh()");

        // 阶段 1: 准备刷新
        prepareRefresh();

        // 阶段 2: 获取 BeanFactory
        obtainFreshBeanFactory();

        // 阶段 3: 准备 BeanFactory
        prepareBeanFactory();

        // 阶段 4: BeanFactory 后置处理（子类扩展点）
        postProcessBeanFactory();

        // 阶段 5: 调用 BeanFactoryPostProcessor（修改 Bean 定义的最后机会）
        // 阶段1到阶段5都是在做BeanFactory和
        invokeBeanFactoryPostProcessors();

        // 阶段 6: 注册 BeanPostProcessor（AOP 代理创建的关键）
        registerBeanPostProcessors();

        // 阶段 7: 初始化消息源（国际化）
        initMessageSource();

        // 阶段 8: 初始化事件多播器（事件发布机制）
        initApplicationEventMulticaster();

        // 阶段 9: 子类扩展点（如 Web 容器启动）
        onRefresh();

        // 阶段 10: 注册监听器
        registerListeners();

        // 阶段 11: 实例化所有单例 Bean（最重要的阶段）
        finishBeanFactoryInitialization();

        // 阶段 12: 完成刷新，发布事件
        finishRefresh();

        System.out.println("\n========== Spring Container Initialization Completed ==========\n");
    }

    /**
     * 阶段 1: 准备刷新
     *
     * 设计原因：初始化容器的基础环境
     * 1. 记录启动时间（用于监控和日志）
     * 2. 初始化属性源（Environment，如系统属性、环境变量）
     * 3. 验证必需的属性是否存在（如数据库连接配置）
     */
    protected void prepareRefresh() {
        System.out.println("\n[Phase 1] prepareRefresh()");
        System.out.println("  - Setting startup date");
        System.out.println("  - Initializing property sources");
        System.out.println("  - Validating required properties");
        for (int i = 0, size = environment.getPropertySources().size(); i < size; i++) {
            com.spring.simulation.environment.PropertySource propertySource = environment.getPropertySources().get(i);
            System.out.println("    * Loaded PropertySource: " + propertySource.getClass().getSimpleName()
                    + " (order=" + propertySource.getOrder() + ")");
        }
    }

    /**
     * 阶段 2: 获取 BeanFactory
     *
     * 设计原因：加载 Bean 定义
     * 1. 刷新 BeanFactory（如果已存在则销毁重建）
     * 为什么要先销毁？
     * - BeanFactory 里不只是“定义”，还持有上一轮 refresh 的运行时状态
     * - 如果不先清理，旧的单例对象、三级缓存、后处理器等状态会污染新一轮启动
     * - refresh 的目标是拿到一个“干净的工厂”，确保本轮容器初始化结果可预测
     *
     * 一般需要销毁/清理的内容：
     * - singletonObjects：已经完成初始化的单例 Bean
     * - earlySingletonObjects：循环依赖时提前暴露的早期引用
     * - singletonFactories：三级缓存中的 Bean 工厂
     * - singletonsCurrentlyInCreation：正在创建中的 Bean 标记
     * - beanPostProcessors：上一轮注册的 BeanPostProcessor
     * - beanDefinitions：上一轮加载的 Bean 定义
     *
     * 实际框架里通常还会触发：
     * - DisposableBean.destroy() / 自定义 destroy-method
     * - 连接池、线程池、文件句柄等资源释放
     *
     * 最朴素的重建方式通常就是：
     * - 先销毁旧 BeanFactory 管理的 Bean 和缓存
     * - 再 new 一个新的 BeanFactory
     * - 然后重新加载 BeanDefinition
     * 2. 加载 Bean 定义（从 XML、注解、Java Config 等）
     * - XML：解析 <bean> 标签，提取 beanName、class、scope、依赖关系等元数据
     * - 注解：扫描指定包下的 @Component、@Configuration 等候选组件，生成定义元数据
     * - Java Config：解析 @Configuration 类中的 @Bean 方法，为方法返回值生成定义元数据
     * - 注册：将解析出的 BeanDefinition 按 beanName 保存到 BeanFactory 的定义表中
     *
     * 此阶段只描述“如何创建 Bean”，不会调用构造器或创建实际对象；
     * Bean 的实例化在后续 finishBeanFactoryInitialization() 阶段进行。
     * 3. 返回可用的 BeanFactory
     *
     * 这是 Bean 定义注册的阶段，但 Bean 还未实例化
     */
    protected BeanFactory obtainFreshBeanFactory() throws Exception {
        System.out.println("\n[Phase 2] obtainFreshBeanFactory()");
        destroyOldBeanFactoryIfNecessary();
        refreshBeanFactory();
        System.out.println("  - Loading bean definitions");
        loadBeanDefinitions();
        return beanFactory;
    }

    /**
     * 销毁上一轮 refresh 遗留的 BeanFactory 运行时状态。
     *
     * 这里只负责清理旧工厂，不负责创建新工厂。
     */
    protected void destroyOldBeanFactoryIfNecessary() throws Exception {
        if (this.beanFactory != null) {
            System.out.println("  - Destroying existing BeanFactory state");
            beanFactory.destroySingletons();
        }
        registeredBeanNames.clear();
    }

    /**
     * 创建一个全新的 BeanFactory，并完成最基础的上下文绑定。
     *
     * 这里只负责“拿到一个干净的工厂”，不负责加载 BeanDefinition。
     */
    protected void refreshBeanFactory() {
        System.out.println("  - Refreshing BeanFactory");
        this.beanFactory = new BeanFactory();
    }

    /**
     * 加载 BeanDefinition。
     *
     * 基类默认不从外部来源加载定义，只使用编程式注册进来的 BeanDefinition。
     * 具体上下文（如注解配置、XML 配置）应覆盖这个方法，接入自己的定义加载策略。
     */
    protected void loadBeanDefinitions() throws Exception {
        System.out.println("  - No external bean definition source configured, using registered definitions only");
    }

    /**
     * 阶段 3: 准备 BeanFactory
     *
     * 设计目的：将 ApplicationContext 持有的基础设施能力配置给 BeanFactory。
     *
     * - ApplicationContext 负责持有并组织 Environment、类加载器、资源加载、事件发布等基础设施。
     * - BeanFactory 负责在创建和管理业务 Bean 时使用这些能力。
     * - prepareBeanFactory() 就是两者的能力装配阶段：不创建业务 Bean，只让 BeanFactory 具备
     * 解析配置、加载类、处理生命周期回调等基础能力。
     *
     * 1. 设置类加载器（用于加载 Bean 的 Class）
     * 2. 注册默认的 BeanPostProcessor（如处理 Aware 接口的处理器）
     * 3. 注册环境相关的 Bean（Environment、SystemProperties 等）
     *
     * 与 obtainFreshBeanFactory() 的区别：
     * - obtainFreshBeanFactory() 负责销毁旧工厂、创建新工厂，并加载用户声明的 BeanDefinition；
     * 它解决的是“业务 Bean 如何被定义”。
     * - prepareBeanFactory() 在新工厂和用户定义都准备好后，为工厂补充 ApplicationContext
     * 提供的基础设施；它解决的是“工厂具备哪些管理 Bean 的能力”。
     *
     * 例如 environment 是 ApplicationContext 已持有的对象，不是扫描得到的业务 Bean。
     * 在此阶段注册，既能确保新 BeanFactory 已存在，也能先检查用户是否已声明同名 Bean，
     * 避免默认基础设施覆盖用户定义。此时仍不会创建普通业务 Bean。
     */
    protected void prepareBeanFactory() {
        // 1. Environment 使用同一实例注册为 singleton（已实现）
        // 2. 设置默认 ClassLoader（已实现）
        // 3. 将 ApplicationContextAware 回调抽为 ApplicationContextAwareProcessor（已实现）
        // 4. 支持按类型注入 BeanFactory / ApplicationContext（已实现）
        // 5. 配置占位符与表达式解析器（已实现，@Value 注入待接入）
        // 6. 最后再考虑监听器探测、LoadTimeWeaver

        System.out.println("\n[Phase 3] prepareBeanFactory()");
        // 统一动态加载 Bean 类和创建代理类时使用的 ClassLoader，避免各扩展点自行选择导致类型不一致。
        configureBeanClassLoader();
        // 将 ${...} 和 #{...} 的解析集中到 BeanFactory，供后续 BeanPostProcessor 统一处理 @Value。
        configureExpressionParser();
        // 注册容器自身，供业务 Bean 通过 @Autowired 按类型获取基础设施对象。
        registerResolvableDependencies();
        // 注册上下文级后处理器；后续每个业务 Bean 创建时都会经过它，按需触发 ApplicationContextAware 回调。
        registerApplicationContextAwareProcessor();
        // 案例：注册环境相关的 Bean
        registerEnvironmentBean();
    }

    /**
     * 将当前线程的上下文类加载器设置为 BeanFactory 的默认类加载器。
     *
     * 后续按类名加载 Bean 类、扫描第三方模块或创建代理类时，都应统一使用该加载器。
     */
    private void configureBeanClassLoader() {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }
        beanFactory.setBeanClassLoader(classLoader);
        System.out.println("  - Setting class loader: " + classLoader.getClass().getSimpleName());
    }

    /**
     * 配置统一的占位符与表达式解析器。
     *
     * ${...} 通过当前 Context 的 Environment 查找配置，#{...} 交由 SpEL 解析器计算。
     * 具体的 @Value 字段注入将在 BeanPostProcessor 创建 Bean 时调用 BeanFactory.resolveValue()。
     */
    private void configureExpressionParser() {
        beanFactory.setExpressionParser(new com.spring.simulation.spel.SpelExpressionParser(environment));
        System.out.println("  - Configuring placeholder and expression parser");
    }

    /**
     * 注册不需要 BeanDefinition 的容器基础设施，避免将它们误当成普通业务 Bean 创建。
     */
    private void registerResolvableDependencies() {
        beanFactory.registerResolvableDependency(BeanFactory.class, beanFactory);
        beanFactory.registerResolvableDependency(ApplicationContext.class, this);
        System.out.println("  - Registering resolvable dependencies: BeanFactory, ApplicationContext");
    }

    /**
     * 提前注册上下文级 Aware 回调处理器，使其在业务 Bean 初始化前生效。
     */
    private void registerApplicationContextAwareProcessor() {
        beanFactory.addBeanPostProcessor(new ApplicationContextAwareProcessor(this));
        System.out.println("  - Registering ApplicationContextAwareProcessor");
    }

    /**
     * 注册环境 Bean
     * 模拟 Spring 注册 Environment、SystemProperties、SystemEnvironment 等内置 Bean
     */
    private void registerEnvironmentBean() {
        if (beanFactory.containsBean("environment")) {
            System.out.println("    * Using existing environment bean");
            return;
        }

        // 直接注册 ApplicationContext 已持有的 Environment，避免 BeanFactory 再创建一个新实例。
        beanFactory.registerSingleton("environment", environment);

        System.out.println("    * Registered environment bean: 'environment' -> " + Environment.class.getSimpleName());
        System.out.println("      (与 ApplicationContext 共享同一个实例，用于访问系统属性、环境变量等)");
    }

    /**
     * 阶段 4: BeanFactory 后置处理
     *
     * 与 prepareBeanFactory() 的区别：前者由父类统一执行，装配类加载器、Environment 等
     * 所有 Context 都需要的基础设施；本方法紧随其后，作为子类钩子补充运行环境专属能力，
     * 避免父类依赖 Web、Reactive 等具体环境。随后才执行 BeanFactoryPostProcessor。
     * 例如 Web Context 可在这里注册 Scope、Servlet 可解析依赖或专属 BeanPostProcessor。
     */
    protected void postProcessBeanFactory() {
        System.out.println("\n[Phase 4] postProcessBeanFactory()");
        System.out.println("  - Hook for subclasses to modify BeanFactory");
    }

    /**
     * 阶段 5: 发现并执行 BeanFactoryPostProcessor。
     *
     * 设计目的：把“修改 Bean 创建配方”的扩展能力从 BeanFactory 核心逻辑中抽离出来。
     * 后处理器操作的是 BeanDefinition（Bean 的“配方”），可统一替换属性占位符、调整 scope
     * 或补充定义；新增这类框架能力时无需改动具体 Bean 的创建流程。
     *
     * 执行时机：必须在阶段 11 实例化 singleton Bean 前完成。Bean 一旦创建，其构造参数、
     * 注入属性和生命周期回调已按旧定义执行；此后再改 BeanDefinition 会造成“定义与实例不一致”。
     * 本阶段只允许创建后处理器自身，普通业务 Bean 仍延迟到阶段 11，避免过早实例化而错过修改。
     *
     * 当前实现先执行编程式注册的处理器，再从 BeanDefinition 自动发现并执行处理器。
     * 完整 Spring 还会优先、多轮执行可注册新定义的 BeanDefinitionRegistryPostProcessor，
     * 并按 PriorityOrdered、Ordered 和普通处理器排序，保证配置解析等基础设施先就绪。
     */
    protected void invokeBeanFactoryPostProcessors() throws Exception {
        System.out.println("\n[Phase 5] invokeBeanFactoryPostProcessors()");
        System.out.println("  - Invoking BeanDefinitionRegistryPostProcessors");
        System.out.println("  - Invoking BeanFactoryPostProcessors");
        System.out.println("  - Number of programmatically added BeanFactoryPostProcessors: "
                + beanFactoryPostProcessors.size());

        // 1. 执行 ApplicationContext.addBeanFactoryPostProcessor(...) 手动添加的处理器。
        for (BeanFactoryPostProcessor processor : beanFactoryPostProcessors) {
            System.out.println("    - Executing programmatic processor: " + processor.getClass().getSimpleName());
            processor.postProcessBeanFactory(beanFactory);
        }

        // 2. 模拟 Spring：从已注册的 BeanDefinition 中发现 BeanFactoryPostProcessor 类型的 Bean。
        // 此时只创建后处理器本身，普通业务 Bean 仍要留到阶段 11 才实例化。
        List<BeanFactoryPostProcessor> discoveredProcessors = new ArrayList<>();
        for (BeanDefinition beanDefinition : beanFactory.getBeanDefinitions().values()) {
            if (BeanFactoryPostProcessor.class.isAssignableFrom(beanDefinition.getBeanClass())) {
                BeanFactoryPostProcessor processor = (BeanFactoryPostProcessor) beanFactory
                        .getBean(beanDefinition.getBeanName());
                discoveredProcessors.add(processor);
                System.out.println("    - Discovered BeanFactoryPostProcessor bean: "
                        + beanDefinition.getBeanName());
            }
        }

        // 3. 执行自动发现的处理器。完整 Spring 还会处理
        // BeanDefinitionRegistryPostProcessor 及 PriorityOrdered / Ordered 排序。
        for (BeanFactoryPostProcessor processor : discoveredProcessors) {
            System.out.println("    - Executing discovered processor: " + processor.getClass().getSimpleName());
            processor.postProcessBeanFactory(beanFactory);
        }
        System.out.println("  - Processing @Configuration classes");
    }

    /**
     * 阶段 6: 注册 BeanPostProcessor
     *
     * 设计原因：这是 AOP 实现的关键阶段！
     * 1. 从容器中找到所有 BeanPostProcessor 类型的 Bean
     * 2. 按优先级排序（PriorityOrdered > Ordered > 普通）
     * 3. 注册到 BeanFactory 中
     *
     * 为什么 BeanPostProcessor 这么重要？
     * - AOP 代理就是通过 BeanPostProcessor 创建的
     * - @Autowired、@Value 等注解的处理也是通过 BeanPostProcessor
     * - 几乎所有的 Bean 增强功能都依赖 BeanPostProcessor
     *
     * 注意：这里只是注册，真正执行是在 Bean 创建时（阶段 11）
     */
    protected void registerBeanPostProcessors() throws Exception {
        System.out.println("\n[Phase 6] registerBeanPostProcessors()");

        // 步骤 1: 注册手动添加的 BeanPostProcessor（编程式添加的）
        System.out.println("  - Registering programmatically added BeanPostProcessors");
        for (BeanPostProcessor processor : beanPostProcessors) {
            beanFactory.addBeanPostProcessor(processor);
            System.out.println("    - Registered: " + processor.getClass().getSimpleName());
        }

        // 步骤 2: 从 BeanFactory 中查找所有 BeanPostProcessor 类型的 Bean
        System.out.println("  - Discovering BeanPostProcessor beans from BeanFactory");
        List<String> postProcessorNames = new ArrayList<>();
        for (String beanName : registeredBeanNames) {
            BeanDefinition bd = beanFactory.getBeanDefinition(beanName);
            if (bd != null && BeanPostProcessor.class.isAssignableFrom(bd.getBeanClass())) {
                postProcessorNames.add(beanName);
            }
        }

        // 步骤 3: 按优先级分类（模拟 PriorityOrdered > Ordered > 普通）
        List<BeanPostProcessor> priorityOrderedPostProcessors = new ArrayList<>();
        List<BeanPostProcessor> orderedPostProcessors = new ArrayList<>();
        List<BeanPostProcessor> regularPostProcessors = new ArrayList<>();

        for (String ppName : postProcessorNames) {
            BeanPostProcessor pp = (BeanPostProcessor) beanFactory.getBean(ppName);

            // 简化实现：根据类名判断优先级
            if (pp.getClass().getSimpleName().contains("Priority")) {
                priorityOrderedPostProcessors.add(pp);
            } else if (pp.getClass().getSimpleName().contains("Ordered")) {
                orderedPostProcessors.add(pp);
            } else {
                regularPostProcessors.add(pp);
            }
        }

        // 步骤 4: 按优先级顺序注册 (通过 List 顺序实现优先级)
        System.out.println("  - Registering BeanPostProcessors by priority:");

        // 先注册 PriorityOrdered
        for (BeanPostProcessor pp : priorityOrderedPostProcessors) {
            beanFactory.addBeanPostProcessor(pp);
            System.out.println("    - [PriorityOrdered] " + pp.getClass().getSimpleName());
        }

        // 再注册 Ordered
        for (BeanPostProcessor pp : orderedPostProcessors) {
            beanFactory.addBeanPostProcessor(pp);
            System.out.println("    - [Ordered] " + pp.getClass().getSimpleName());
        }

        // 最后注册普通的
        for (BeanPostProcessor pp : regularPostProcessors) {
            beanFactory.addBeanPostProcessor(pp);
            System.out.println("    - [Regular] " + pp.getClass().getSimpleName());
        }

        System.out.println("  - Total BeanPostProcessors registered: " +
                (beanPostProcessors.size() + priorityOrderedPostProcessors.size() +
                        orderedPostProcessors.size() + regularPostProcessors.size()));
    }

    /**
     * 阶段 7: 初始化消息源
     *
     * 设计原因：国际化支持
     * MessageSource 用于根据 Locale 获取不同语言的消息
     */
    protected void initMessageSource() {
        System.out.println("\n[Phase 7] initMessageSource()");
        System.out.println("  - Initializing MessageSource for i18n");
    }

    /**
     * 阶段 8: 初始化事件多播器
     *
     * 设计原因：事件发布机制的基础设施
     * ApplicationEventMulticaster 负责将事件分发给所有监听器
     */
    protected void initApplicationEventMulticaster() {
        System.out.println("\n[Phase 8] initApplicationEventMulticaster()");
        System.out.println("  - Initializing event multicaster");
    }

    /**
     * 阶段 9: 子类扩展点
     *
     * 设计原因：特定类型容器的初始化
     * 例如：SpringBoot 的 Web 容器（Tomcat、Jetty）就在这里启动
     */
    protected void onRefresh() {
        System.out.println("\n[Phase 9] onRefresh()");
        System.out.println("  - Hook for subclasses (e.g., web container startup)");
    }

    /**
     * 阶段 10: 注册监听器
     *
     * 设计原因：注册应用事件监听器
     * 监听器可以响应容器事件（如 ContextRefreshedEvent、ContextClosedEvent）
     */
    protected void registerListeners() {
        System.out.println("\n[Phase 10] registerListeners()");
        System.out.println("  - Registering application listeners");
    }

    /**
     * 阶段 11: 完成 BeanFactory 初始化（最重要的阶段）
     *
     * 设计原因：实例化所有非懒加载的单例 Bean
     * 1. 冻结 Bean 定义（不再允许修改）
     * 2. 实例化所有单例 Bean（触发完整的 Bean 生命周期）
     *
     * 为什么要在这个阶段实例化？
     * 1. 前面的阶段已经完成了所有准备工作（Bean 定义、BeanPostProcessor 等）
     * 2. 此时实例化可以确保所有扩展点都已就绪
     * 3. 提前实例化可以在启动时发现问题，而不是运行时
     *
     * 这个阶段会触发：
     * - Bean 的完整生命周期（实例化、依赖注入、初始化等）
     * - 循环依赖的解决
     * - AOP 代理的创建
     */
    protected void finishBeanFactoryInitialization() throws Exception {
        System.out.println("\n[Phase 11] finishBeanFactoryInitialization()");
        System.out.println("  - Freezing bean definitions");
        System.out.println("  - Instantiating all remaining singleton beans");
        instantiateSingletons();
    }

    /**
     * 阶段 12: 完成刷新
     *
     * 设计原因：容器启动的收尾工作
     * 1. 发布 ContextRefreshedEvent 事件（通知监听器容器已就绪）
     * 2. 启动生命周期处理器（如定时任务）
     * 3. 清理资源缓存
     */
    protected void finishRefresh() {
        System.out.println("\n[Phase 12] finishRefresh()");
        System.out.println("  - Publishing ContextRefreshedEvent");
        System.out.println("  - Starting lifecycle processors");
    }

    private void instantiateSingletons() throws Exception {
        for (String beanName : registeredBeanNames) {
            BeanDefinition beanDefinition = beanFactory.getBeanDefinition(beanName);
            if (beanDefinition != null && beanDefinition.isSingleton()) {
                beanFactory.getBean(beanName);
            }
        }
    }

    public void registerBeanDefinition(String beanName, Class<?> beanClass) {
        BeanDefinition beanDefinition = new BeanDefinition(beanName, beanClass);
        beanFactory.registerBeanDefinition(beanName, beanDefinition);
        registeredBeanNames.add(beanName);
    }

    public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition) {
        beanFactory.registerBeanDefinition(beanName, beanDefinition);
        registeredBeanNames.add(beanName);
    }

    public void addBeanPostProcessor(BeanPostProcessor processor) {
        beanPostProcessors.add(processor);
    }

    public void addBeanFactoryPostProcessor(BeanFactoryPostProcessor processor) {
        beanFactoryPostProcessors.add(processor);
    }

    public Object getBean(String beanName) throws Exception {
        return beanFactory.getBean(beanName);
    }

    public BeanFactory getBeanFactory() {
        return beanFactory;
    }

    public void close() throws Exception {
        System.out.println("\n========== Closing Application Context ==========");
        beanFactory.destroySingletons();
    }
}
