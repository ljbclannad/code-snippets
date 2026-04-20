package com.spring.simulation.ioc;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class BeanFactory {
    // Bean 定义注册表：存储所有 Bean 的元数据（类信息、作用域等）
    private Map<String, BeanDefinition> beanDefinitions = new HashMap<>();

    // 一级缓存（singletonObjects）：存储完全初始化完成的单例 Bean
    // 设计原因：单例模式的核心，保证同一个 Bean 在容器中只有一个实例
    private Map<String, Object> singletonObjects = new HashMap<>();

    // 二级缓存（earlySingletonObjects）：存储早期暴露的 Bean 引用（已实例化，未完成初始化）
    // 设计原因：缓存从三级缓存中获取的早期 Bean 引用，避免重复调用工厂方法
    // 当循环依赖发生时，从三级缓存获取 Bean 后会放入二级缓存
    private Map<String, Object> earlySingletonObjects = new HashMap<>();

    // 三级缓存（singletonFactories）：存储 Bean 工厂（ObjectFactory）
    // 设计原因：这是解决循环依赖的关键！
    // 为什么需要三级缓存？
    // 1. 延迟代理对象的创建：只有在真正发生循环依赖时才创建代理对象
    // 2. 保证代理对象的唯一性：通过工厂方法确保多次获取返回同一个代理对象
    // 3. 支持 AOP：如果 Bean 需要被代理，工厂方法会返回代理对象而不是原始对象
    //
    // 三级缓存的工作流程：
    // A 依赖 B，B 依赖 A（假设 A 需要被 AOP 代理）
    // 1. 创建 A 的实例，将 A 的工厂方法放入三级缓存
    // 2. 填充 A 的属性，发现需要 B，开始创建 B
    // 3. 创建 B 的实例，将 B 的工厂方法放入三级缓存
    // 4. 填充 B 的属性，发现需要 A
    // 5. 从三级缓存获取 A 的工厂方法，调用工厂方法获取 A 的早期引用（可能是代理对象）
    // 6. 将 A 的早期引用放入二级缓存，从三级缓存移除
    // 7. B 完成初始化，放入一级缓存
    // 8. A 继续完成初始化，放入一级缓存
    private Map<String, Supplier<Object>> singletonFactories = new HashMap<>();

    // 正在创建的 Bean 集合：用于检测循环依赖
    private Map<String, Boolean> singletonsCurrentlyInCreation = new HashMap<>();

    // BeanPostProcessor 列表：Bean 初始化前后的扩展点
    // 设计原因：提供统一的扩展机制，AOP 代理就是在这里创建的
    private List<BeanPostProcessor> beanPostProcessors = new ArrayList<>();

    // 标志位：是否正在创建 BeanPostProcessor
    // 设计原因：BeanPostProcessor 的创建不应该被其他 BeanPostProcessor 处理，避免循环依赖
    private boolean creatingBeanPostProcessor = false;

    private ApplicationContext applicationContext;

    public void registerBeanDefinition(String beanName, BeanDefinition beanDefinition) {
        beanDefinitions.put(beanName, beanDefinition);
        System.out.println("[IoC] Registered bean definition: " + beanName);
    }

    public void addBeanPostProcessor(BeanPostProcessor processor) {
        beanPostProcessors.add(processor);
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public Object getBean(String beanName) throws Exception {
        if (!beanDefinitions.containsKey(beanName)) {
            throw new Exception("Bean not found: " + beanName);
        }

        BeanDefinition beanDefinition = beanDefinitions.get(beanName);

        // 单例 Bean 的获取逻辑（三级缓存查找）
        if (beanDefinition.isSingleton()) {
            // 尝试从三级缓存中获取 Bean
            Object singleton = getSingleton(beanName);
            if (singleton != null) {
                return singleton;
            }

            // 缓存中都没有，创建新的 Bean
            return getSingleton(beanName, () -> {
                try {
                    return createBean(beanName, beanDefinition);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        } else if (beanDefinition.isPrototype()) {
            // Prototype 作用域：每次都创建新实例
            System.out.println("[IoC] Creating new prototype instance: " + beanName);
            return createBean(beanName, beanDefinition);
        } else {
            // 其他作用域（request、session、application）
            // 简化实现：当作 prototype 处理
            System.out
                    .println("[IoC] Creating new instance for scope '" + beanDefinition.getScope() + "': " + beanName);
            return createBean(beanName, beanDefinition);
        }
    }

    /**
     * 从三级缓存中获取单例 Bean
     *
     * 查找顺序：
     * 1. 一级缓存（singletonObjects）：完全初始化的 Bean
     * 2. 二级缓存（earlySingletonObjects）：早期暴露的 Bean
     * 3. 三级缓存（singletonFactories）：Bean 工厂
     */
    private Object getSingleton(String beanName) {
        // 第一步：从一级缓存获取完全初始化的 Bean
        Object singletonObject = singletonObjects.get(beanName);
        if (singletonObject != null) {
            System.out.println("[IoC] Returning cached singleton from L1: " + beanName);
            return singletonObject;
        }

        // 第二步：从二级缓存获取早期 Bean 引用 （一级缓存中没有）
        // 只有在 Bean 正在创建中时才会查找二级和三级缓存（解决循环依赖）
        if (singletonsCurrentlyInCreation.containsKey(beanName)) {
            singletonObject = earlySingletonObjects.get(beanName);
            if (singletonObject != null) {
                System.out.println(
                        "[IoC] Returning early singleton from L2 (resolving circular dependency): " + beanName);
                return singletonObject;
            }

            // 第三步：从三级缓存获取 Bean 工厂
            Supplier<Object> singletonFactory = singletonFactories.get(beanName);
            if (singletonFactory != null) {
                System.out.println(
                        "[IoC] Getting early singleton from L3 factory (resolving circular dependency): " + beanName);
                // 调用工厂方法获取早期 Bean 引用（可能是代理对象）
                singletonObject = singletonFactory.get();
                // 将早期引用放入二级缓存
                earlySingletonObjects.put(beanName, singletonObject);
                // 从三级缓存移除
                singletonFactories.remove(beanName);
                return singletonObject;
            }
        }

        return null;
    }

    /**
     * 获取单例 Bean（如果不存在则创建）
     *
     * @param beanName         Bean 名称
     * @param singletonFactory Bean 工厂
     * @return 单例 Bean
     */
    private Object getSingleton(String beanName, Supplier<Object> singletonFactory) {
        // 标记 Bean 正在创建中
        singletonsCurrentlyInCreation.put(beanName, true);

        try {
            // 调用工厂方法创建 Bean
            Object singletonObject = singletonFactory.get();

            // 创建完成后放入一级缓存
            singletonObjects.put(beanName, singletonObject);
            // 从二级和三级缓存移除
            earlySingletonObjects.remove(beanName);
            singletonFactories.remove(beanName);

            return singletonObject;
        } finally {
            // 移除创建中标记
            singletonsCurrentlyInCreation.remove(beanName);
        }
    }

    /**
     * Bean 创建的完整生命周期（Spring 最核心的流程）
     *
     * 为什么要分这么多步骤？
     * 1. 解耦：每个步骤职责单一，便于扩展和维护
     * 2. 扩展性：通过 BeanPostProcessor、Aware 等机制，允许在不修改核心代码的情况下扩展功能
     * 3. AOP 实现：BeanPostProcessor 是 AOP 代理创建的关键时机
     * 4. 循环依赖：实例化和初始化分离，才能通过早期引用解决循环依赖
     */
    private Object createBean(String beanName, BeanDefinition beanDefinition) throws Exception {
        System.out.println("\n[Bean Lifecycle] ========== Creating Bean: " + beanName + " ==========");

        // ========== 步骤 1: 实例化 Bean ==========
        System.out.println("[Bean Lifecycle] 1. Instantiate bean");
        Object bean;

        // 检查是否通过工厂方法创建（@Bean 方法）
        if (beanDefinition.getFactoryMethod() != null) {
            // 通过 @Bean 方法创建
            Object factoryBean = getBean(beanDefinition.getFactoryBeanName());
            bean = beanDefinition.getFactoryMethod().invoke(factoryBean);
            System.out.println("   [Factory Method] Created bean via @Bean method: " +
                    beanDefinition.getFactoryMethod().getName());
        } else {
            // 通过构造器创建对象（支持构造注入）
            bean = instantiateBean(beanDefinition);
        }

        // 关键设计：实例化后立即将 Bean 工厂放入三级缓存（早期暴露）
        // 设计原因：允许其他 Bean 在循环依赖时获取到这个半成品 Bean
        // 为什么使用工厂而不是直接放 Bean？
        // 1. 延迟代理对象的创建：只有在真正发生循环依赖时才创建代理
        // 2. 支持 AOP：如果 Bean 需要被代理，工厂方法会返回代理对象
        if (beanDefinition.isSingleton()) {
            Object finalBean = bean;
            addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, beanDefinition, finalBean));
        }

        // ========== 步骤 2: 属性填充（依赖注入）==========
        // 设计原因：实例化和属性填充分离，是解决循环依赖的关键
        System.out.println("[Bean Lifecycle] 2. Populate properties (dependency injection)");
        populateBean(bean);

        // ========== 步骤 3: Aware 接口回调 ==========
        // 设计原因：让 Bean 感知容器内部对象（BeanName、BeanFactory、ApplicationContext）
        System.out.println("[Bean Lifecycle] 3. Invoke Aware interfaces");
        invokeAwareMethods(beanName, bean);

        // ========== 步骤 4: BeanPostProcessor 前置处理 ==========
        // 设计原因：在初始化前提供扩展点，可以修改 Bean 实例
        System.out.println("[Bean Lifecycle] 4. BeanPostProcessor.postProcessBeforeInitialization");
        bean = applyBeanPostProcessorsBeforeInitialization(bean, beanName);

        // ========== 步骤 5: 初始化回调 ==========
        // 设计原因：Bean 的自定义初始化逻辑（如连接数据库、加载配置等）
        System.out.println("[Bean Lifecycle] 5. Invoke InitializingBean.afterPropertiesSet");
        invokeInitMethods(bean);

        // ========== 步骤 6: BeanPostProcessor 后置处理 ==========
        // 设计原因：初始化后的扩展点，这是 AOP 代理创建的关键时机！
        // Spring AOP 在这里将原始 Bean 替换为代理对象
        System.out.println("[Bean Lifecycle] 6. BeanPostProcessor.postProcessAfterInitialization");
        bean = applyBeanPostProcessorsAfterInitialization(bean, beanName);

        System.out.println("[Bean Lifecycle] Bean creation completed: " + beanName + "\n");

        return bean;
    }

    /**
     * 实例化 Bean（支持构造注入）
     *
     * 构造注入 vs 字段注入：
     * 1. 构造注入：依赖在对象创建时注入，无法解决循环依赖
     * 2. 字段注入：对象创建后再注入依赖，可以通过三级缓存解决循环依赖
     *
     * 构造器选择策略：
     * 1. 优先选择带 @Autowired 注解的构造器
     * 2. 如果没有 @Autowired，选择唯一的有参构造器
     * 3. 如果有多个构造器且都没有 @Autowired，选择无参构造器
     */
    private Object instantiateBean(BeanDefinition beanDefinition) throws Exception {
        Class<?> beanClass = beanDefinition.getBeanClass();
        java.lang.reflect.Constructor<?>[] constructors = beanClass.getDeclaredConstructors();

        // 选择合适的构造器
        java.lang.reflect.Constructor<?> targetConstructor = determineConstructor(constructors);
        targetConstructor.setAccessible(true); // 允许访问非 public 构造器

        if (targetConstructor.getParameterCount() == 0) {
            // 无参构造器
            System.out.println("   [Constructor] Using no-arg constructor");
            return targetConstructor.newInstance();
        } else {
            // 有参构造器：构造注入
            System.out.println("   [Constructor Injection] Using constructor with " +
                    targetConstructor.getParameterCount() + " parameters");
            Object[] args = resolveConstructorArguments(targetConstructor);
            return targetConstructor.newInstance(args);
        }
    }

    /**
     * 确定使用哪个构造器
     *
     * 选择策略：
     * 1. 如果有 @Autowired 注解的构造器，使用它
     * 2. 如果只有一个有参构造器，使用它
     * 3. 如果有多个构造器，使用无参构造器
     */
    private java.lang.reflect.Constructor<?> determineConstructor(java.lang.reflect.Constructor<?>[] constructors) {
        // 策略 1: 查找带 @Autowired 的构造器
        for (java.lang.reflect.Constructor<?> constructor : constructors) {
            if (constructor.isAnnotationPresent(Autowired.class)) {
                System.out.println("   [Constructor Selection] Found @Autowired constructor");
                return constructor;
            }
        }

        // 策略 2: 如果只有一个有参构造器，使用它
        if (constructors.length == 1) {
            return constructors[0];
        }

        // 策略 3: 查找无参构造器
        for (java.lang.reflect.Constructor<?> constructor : constructors) {
            if (constructor.getParameterCount() == 0) {
                return constructor;
            }
        }

        // 如果有多个有参构造器且没有 @Autowired，使用第一个
        return constructors[0];
    }

    /**
     * 解析构造器参数（递归获取依赖）
     *
     * 注意：构造注入无法解决循环依赖！
     * 如果 A 的构造器需要 B，B 的构造器需要 A，会导致死锁
     *
     * 解决方案：
     * 1. 使用字段注入代替构造注入
     * 2. 重新设计类结构，消除循环依赖
     */
    private Object[] resolveConstructorArguments(java.lang.reflect.Constructor<?> constructor) throws Exception {
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];

        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> paramType = parameterTypes[i];

            // 生成依赖的 Bean 名称（首字母小写）
            String dependencyName = paramType.getSimpleName();
            dependencyName = Character.toLowerCase(dependencyName.charAt(0)) + dependencyName.substring(1);

            // 递归获取依赖（可能触发依赖的创建）
            // 注意：这里无法从三级缓存获取早期引用，因为对象还未创建
            args[i] = getBean(dependencyName);
            System.out.println("      - Resolved constructor parameter [" + i + "]: " +
                    paramType.getSimpleName() + " -> " + dependencyName);
        }

        return args;
    }

    /**
     * 将 Bean 工厂添加到三级缓存
     */
    private void addSingletonFactory(String beanName, Supplier<Object> singletonFactory) {
        if (!singletonObjects.containsKey(beanName)) {
            singletonFactories.put(beanName, singletonFactory);
            earlySingletonObjects.remove(beanName);
            System.out.println("   [L3 Cache] Added singleton factory for: " + beanName);
        }
    }

    /**
     * 获取早期 Bean 引用（可能是代理对象）
     *
     * 这是三级缓存的核心方法！
     * 当发生循环依赖时，会调用这个方法获取早期 Bean 引用
     * 如果 Bean 需要被 AOP 代理，这里会返回代理对象而不是原始对象
     */
    private Object getEarlyBeanReference(String beanName, BeanDefinition beanDefinition, Object bean) {
        Object exposedObject = bean;

        // 这里可以应用 BeanPostProcessor 来创建代理对象
        // 例如：如果 Bean 有 @Transactional 注解，这里会创建事务代理
        // 简化实现：直接返回原始对象
        // 真实 Spring 会调用
        // SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference()

        System.out.println("   [Early Reference] Exposing early reference for: " + beanName);
        return exposedObject;
    }

    /**
     * 属性填充：依赖注入的核心实现
     *
     * 支持三种注入方式：
     * 1. 字段注入：@Autowired 标注在字段上
     * 2. Setter 注入：@Autowired 标注在 setter 方法上
     * 3. 构造器注入：@Autowired 标注在构造器上（在实例化阶段处理）
     *
     * 为什么使用字段注入而不是构造器注入？
     * 1. 构造器注入无法解决循环依赖（A 的构造器需要 B，B 的构造器需要 A，死锁）
     * 2. 字段注入允许先创建对象，再填充属性，配合三级缓存可以解决循环依赖
     *
     * 注入流程：
     * 1. 扫描所有字段，找到 @Autowired 注解
     * 2. 通过 getBean() 获取依赖对象（可能触发依赖对象的创建）
     * 3. 通过反射设置字段值
     */
    private void populateBean(Object bean) throws Exception {
        // 1. 字段注入
        Field[] fields = bean.getClass().getDeclaredFields();
        for (Field field : fields) {
            if (field.isAnnotationPresent(Autowired.class)) {
                field.setAccessible(true);
                String dependencyName = field.getType().getSimpleName();
                dependencyName = Character.toLowerCase(dependencyName.charAt(0)) + dependencyName.substring(1);

                Object dependency = getBean(dependencyName);
                field.set(bean, dependency);
                System.out.println(
                        "   [Autowired] Injected " + dependencyName + " into " + bean.getClass().getSimpleName()
                                + " (field)");
            }
        }

        // 2. Setter 方法注入
        java.lang.reflect.Method[] methods = bean.getClass().getDeclaredMethods();
        for (java.lang.reflect.Method method : methods) {
            if (method.isAnnotationPresent(Autowired.class)) {
                // 验证是否为 setter 方法（方法名以 set 开头，且只有一个参数）
                if (method.getName().startsWith("set") && method.getParameterCount() == 1) {
                    method.setAccessible(true);
                    Class<?> paramType = method.getParameterTypes()[0];

                    String dependencyName = paramType.getSimpleName();
                    dependencyName = Character.toLowerCase(dependencyName.charAt(0)) + dependencyName.substring(1);

                    Object dependency = getBean(dependencyName);
                    method.invoke(bean, dependency);
                    System.out.println(
                            "   [Autowired] Injected " + dependencyName + " into " + bean.getClass().getSimpleName() +
                                    " (setter: " + method.getName() + ")");
                }
            }
        }
    }

    /**
     * Aware 接口回调：让 Bean 感知容器
     *
     * 设计原因：
     * 1. 有些 Bean 需要知道自己在容器中的名称（BeanNameAware）
     * 2. 有些 Bean 需要手动从容器获取其他 Bean（BeanFactoryAware）
     * 3. 有些 Bean 需要发布事件或访问环境变量（ApplicationContextAware）
     *
     * 为什么不直接在构造器中传入？
     * 因为这些是可选的，不是所有 Bean 都需要，通过接口实现按需获取更灵活
     */
    private void invokeAwareMethods(String beanName, Object bean) {
        if (bean instanceof BeanNameAware) {
            System.out.println("   [Aware] Invoking BeanNameAware.setBeanName");
            ((BeanNameAware) bean).setBeanName(beanName);
        }

        if (bean instanceof BeanFactoryAware) {
            System.out.println("   [Aware] Invoking BeanFactoryAware.setBeanFactory");
            ((BeanFactoryAware) bean).setBeanFactory(this);
        }

        if (bean instanceof ApplicationContextAware && applicationContext != null) {
            System.out.println("   [Aware] Invoking ApplicationContextAware.setApplicationContext");
            ((ApplicationContextAware) bean).setApplicationContext(applicationContext);
        }
    }

    /**
     * BeanPostProcessor 前置处理
     *
     * 设计原因：提供初始化前的扩展点
     * 应用场景：
     * 1. @PostConstruct 注解的处理
     * 2. @Value 注解的属性注入
     * 3. 验证 Bean 的配置是否正确
     */
    private Object applyBeanPostProcessorsBeforeInitialization(Object bean, String beanName) throws Exception {
        // 如果正在创建 BeanPostProcessor，跳过 BeanPostProcessor 的应用
        // 避免 BeanPostProcessor 创建时被其他 BeanPostProcessor 处理
        if (creatingBeanPostProcessor) {
            System.out.println("   [BeanFactory] Skipping BeanPostProcessor application for: " + beanName
                    + " (creating BeanPostProcessor)");
            return bean;
        }

        Object result = bean;
        for (BeanPostProcessor processor : beanPostProcessors) {
            result = processor.postProcessBeforeInitialization(result, beanName);
            System.out.println("   [BeanPostProcessor] " + processor.getClass().getSimpleName()
                    + ".postProcessBeforeInitialization");
        }
        return result;
    }

    /**
     * 初始化回调
     *
     * 设计原因：Bean 的自定义初始化逻辑
     * 为什么不在构造器中做？
     * 1. 构造器执行时，依赖注入还未完成，无法使用注入的对象
     * 2. 初始化回调执行时，所有依赖都已注入，可以安全使用
     */
    private void invokeInitMethods(Object bean) throws Exception {
        if (bean instanceof InitializingBean) {
            System.out.println("   [InitializingBean] Invoking afterPropertiesSet");
            ((InitializingBean) bean).afterPropertiesSet();
        }
    }

    /**
     * BeanPostProcessor 后置处理（AOP 代理创建的关键时机）
     *
     * 设计原因：这是 Spring AOP 的核心实现点！
     * 1. 此时 Bean 已经完全初始化（实例化、依赖注入、初始化回调都已完成）
     * 2. 可以安全地创建代理对象，代理对象可以正确拦截所有方法
     * 3. 返回的可能是原始 Bean，也可能是代理对象（对调用者透明）
     *
     * 为什么在这里创建代理而不是实例化时？
     * 1. 代理需要拦截业务方法，而业务方法可能依赖注入的对象，必须等依赖注入完成
     * 2. 如果在实例化时创建代理，二级缓存中存的是代理对象，可能导致循环依赖问题
     *
     * 应用场景：
     * 1. AOP 代理创建（@Transactional、@Async 等）
     * 2. 动态代理增强
     */
    private Object applyBeanPostProcessorsAfterInitialization(Object bean, String beanName) throws Exception {
        // 如果正在创建 BeanPostProcessor，跳过 BeanPostProcessor 的应用
        if (creatingBeanPostProcessor) {
            System.out.println("   [BeanFactory] Skipping BeanPostProcessor application for: " + beanName
                    + " (creating BeanPostProcessor)");
            return bean;
        }

        Object result = bean;
        for (BeanPostProcessor processor : beanPostProcessors) {
            result = processor.postProcessAfterInitialization(result, beanName);
            System.out.println("   [BeanPostProcessor] " + processor.getClass().getSimpleName()
                    + ".postProcessAfterInitialization");
        }
        return result;
    }

    public void destroySingletons() throws Exception {
        System.out.println("\n[Bean Lifecycle] ========== Destroying Singleton Beans ==========");
        for (Map.Entry<String, Object> entry : singletonObjects.entrySet()) {
            Object bean = entry.getValue();
            if (bean instanceof DisposableBean) {
                System.out.println("[DisposableBean] Destroying: " + entry.getKey());
                ((DisposableBean) bean).destroy();
            }
        }
    }

    public Map<String, BeanDefinition> getBeanDefinitions() {
        return beanDefinitions;
    }

    public BeanDefinition getBeanDefinition(String beanName) {
        return beanDefinitions.get(beanName);
    }

    /**
     * 按类型查找 Bean
     * 用于支持 @Autowired 的类型注入
     *
     * @param type 要查找的 Bean 类型
     * @return 匹配的 Bean 实例，如果找不到或找到多个则返回 null
     */
    public Object getBeanByType(Class<?> type) throws Exception {
        Object matchedBean = null;
        String matchedBeanName = null;
        int matchCount = 0;

        // 遍历所有 Bean 定义，查找类型匹配的 Bean
        for (Map.Entry<String, BeanDefinition> entry : beanDefinitions.entrySet()) {
            String beanName = entry.getKey();
            BeanDefinition beanDefinition = entry.getValue();

            // 检查 Bean 类型是否匹配（包括子类和实现类）
            if (type.isAssignableFrom(beanDefinition.getBeanClass())) {
                matchCount++;
                matchedBean = getBean(beanName);
                matchedBeanName = beanName;

                // 如果找到多个匹配的 Bean，抛出异常
                if (matchCount > 1) {
                    throw new RuntimeException("Expected single matching bean of type [" +
                            type.getName() + "] but found " + matchCount);
                }
            }
        }

        if (matchedBean != null) {
            System.out.println("[IoC] Found bean by type: " + type.getSimpleName() + " -> " + matchedBeanName);
        }

        return matchedBean;
    }
}
