# Spring @Autowired 处理流程详解

## 核心问题：Spring 在 refresh() 的哪一步处理 @Autowired？

答案：**分两个阶段**

### 阶段 3: prepareBeanFactory()
**作用：注册 AutowiredAnnotationBeanPostProcessor**

```java
protected void prepareBeanFactory() {
    // 注册 AutowiredAnnotationBeanPostProcessor
    // 这个处理器负责解析 @Autowired、@Value、@Inject 注解
    // ApplicationContext.prepareBeanFactory() 会内建注册
    // com.spring.simulation.ioc.postprocessor.AutowiredAnnotationBeanPostProcessor
}
```

**此时只是注册，还未执行注入！**

### 阶段 11: finishBeanFactoryInitialization()
**作用：实例化所有单例 Bean，触发依赖注入**

```java
protected void finishBeanFactoryInitialization() throws Exception {
    // 实例化所有单例 Bean
    // 在 Bean 创建过程中，会调用 BeanPostProcessor.postProcessBeforeInitialization()
    instantiateSingletons();
}
```

**在这个阶段，处理器会在 Bean 的“属性填充”步骤真正执行依赖注入。**

---

## 完整的 Bean 生命周期与 @Autowired

```
Bean 创建流程：
1. 实例化 Bean (Instantiation)
   └─ 调用构造函数创建对象

2. 填充属性 (Populate Properties)
   └─ InstantiationAwareBeanPostProcessor.postProcessProperties()
      └─ AutowiredAnnotationBeanPostProcessor 扫描 @Autowired 并注入依赖

3. 调用 Aware 接口
   └─ BeanNameAware, BeanFactoryAware 等

4. BeanPostProcessor.postProcessBeforeInitialization()

5. 初始化方法 (Initialization)
   └─ InitializingBean.afterPropertiesSet()
   └─ @PostConstruct
   └─ init-method

6. BeanPostProcessor.postProcessAfterInitialization()
   └─ AOP 代理在这里创建

7. Bean 就绪，可以使用
```

---

## AutowiredAnnotationBeanPostProcessor 实现原理

### 1. 字段注入 (Field Injection)

```java
@Autowired
private UserRepository userRepository;
```

**处理流程：**
```java
private void injectFields(Object bean, String beanName) throws Exception {
    Class<?> clazz = bean.getClass();
    
    // 遍历所有字段
    for (Field field : clazz.getDeclaredFields()) {
        // 检查是否有 @Autowired 注解
        if (field.isAnnotationPresent(Autowired.class)) {
            // 1. 按类型选择唯一候选
            // 2. 多候选时优先选择 @Primary
            // 3. 可使用 @Qualifier("beanName") 指定候选
            Object dependency = beanFactory.resolveDependency(field.getType(), null);
            
            // 3. 通过反射注入
            field.setAccessible(true);
            field.set(bean, dependency);
        }
    }
}
```

### 2. 方法注入 (Method Injection)

```java
@Autowired
public void setDependencies(UserRepository repo, UserService service) {
    this.userRepository = repo;
    this.userService = service;
}
```

**处理流程：**
```java
private void injectMethods(Object bean, String beanName) throws Exception {
    Class<?> clazz = bean.getClass();
    
    // 遍历所有方法
    for (Method method : clazz.getDeclaredMethods()) {
        // 检查是否有 @Autowired 注解
        if (method.isAnnotationPresent(Autowired.class)) {
            // 1. 获取方法参数类型
            Class<?>[] paramTypes = method.getParameterTypes();
            
            // 2. 为每个参数按类型查找依赖
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                args[i] = beanFactory.getBeanByType(paramTypes[i]);
            }
            
            // 3. 调用方法注入
            method.setAccessible(true);
            method.invoke(bean, args);
        }
    }
}
```

---

## 为什么要在 postProcessBeforeInitialization 阶段处理？

### 时机选择的原因：

1. **Bean 已经实例化**
   - 对象已经创建，可以通过反射设置字段

2. **初始化方法还未执行**
   - 确保在 `@PostConstruct` 和 `afterPropertiesSet()` 之前完成注入
   - 这样初始化方法中就可以使用注入的依赖

3. **AOP 代理还未创建**
   - AOP 代理在 `postProcessAfterInitialization` 阶段创建
   - 先注入依赖，再创建代理，保证代理对象也有正确的依赖

### 错误示例：如果在 postProcessAfterInitialization 处理会怎样？

```java
public class UserService {
    @Autowired
    private UserRepository userRepository;
    
    @PostConstruct
    public void init() {
        // ✗ 如果 @Autowired 在 after 阶段处理
        // 这里 userRepository 还是 null！
        userRepository.init(); // NullPointerException!
    }
}
```

---

## 按类型查找的实现：getBeanByType()

```java
public Object getBeanByType(Class<?> type) throws Exception {
    Object matchedBean = null;
    int matchCount = 0;
    
    // 遍历所有 Bean 定义
    for (Map.Entry<String, BeanDefinition> entry : beanDefinitions.entrySet()) {
        BeanDefinition beanDefinition = entry.getValue();
        
        // 检查类型是否匹配（包括子类和实现类）
        if (type.isAssignableFrom(beanDefinition.getBeanClass())) {
            matchCount++;
            matchedBean = getBean(entry.getKey());
            
            // 如果找到多个匹配的 Bean，抛出异常
            if (matchCount > 1) {
                throw new RuntimeException(
                    "Expected single matching bean of type [" + 
                    type.getName() + "] but found " + matchCount
                );
            }
        }
    }
    
    return matchedBean;
}
```

---

## 运行示例

查看 `SpringRefreshDemo.java` 的运行输出，可以清楚地看到：

```
[Phase 6] registerBeanPostProcessors()
  - Registering programmatically added BeanPostProcessors
    - Registered: AutowiredAnnotationBeanPostProcessor  ← 注册处理器

[Phase 11] finishBeanFactoryInitialization()
  - Instantiating all remaining singleton beans

[Bean Lifecycle] ========== Creating Bean: userService ==========
[Bean Lifecycle] 4. BeanPostProcessor.postProcessBeforeInitialization
   [AutowiredAnnotationBeanPostProcessor] Processing @Autowired for: userService
     - Found @Autowired field: userRepository (type: UserRepository)
       → Injecting bean: userRepository
       ✓ Injection completed  ← 真正执行注入
```

---

## 总结

| 阶段 | 步骤 | 作用 |
|------|------|------|
| **阶段 6** | registerBeanPostProcessors() | 注册 AutowiredAnnotationBeanPostProcessor |
| **阶段 11** | finishBeanFactoryInitialization() | 实例化 Bean 时，通过 BeanPostProcessor 执行注入 |

**关键点：**
- 阶段 6 只是准备工具（注册处理器）
- 阶段 11 才是真正使用工具（执行注入）
- 注入发生在 Bean 生命周期的 `postProcessBeforeInitialization` 阶段
- 这样确保依赖在初始化方法执行前就已经注入完成
