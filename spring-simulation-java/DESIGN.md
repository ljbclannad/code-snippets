# Spring 框架核心设计原理详解

本文档详细解释 Spring 框架中几个关键步骤的设计原理和实现细节。

## 目录
1. [三级缓存与循环依赖解决](#1-三级缓存与循环依赖解决)
2. [Bean 生命周期的 6 个阶段](#2-bean-生命周期的-6-个阶段)
3. [ApplicationContext.refresh() 的 12 个阶段](#3-applicationcontextrefresh-的-12-个阶段)
4. [AOP 动态代理与拦截器链](#4-aop-动态代理与拦截器链)
5. [声明式事务的实现原理](#5-声明式事务的实现原理)

---

## 1. 三级缓存与循环依赖解决

### 问题背景
```java
// ServiceA 依赖 ServiceB
class ServiceA {
    @Autowired
    private ServiceB serviceB;
}

// ServiceB 依赖 ServiceA
class ServiceB {
    @Autowired
    private ServiceA serviceA;
}
```

如果没有特殊处理，会陷入死循环：
- 创建 A → 需要 B → 创建 B → 需要 A → 创建 A → ...

### 解决方案：三级缓存

```java
// 一级缓存：完全初始化的 Bean
private Map<String, Object> singletonObjects = new HashMap<>();

// 二级缓存：早期暴露的 Bean（仅实例化，未初始化）
private Map<String, Object> earlySingletonObjects = new HashMap<>();

// 三级缓存：Bean 工厂（本项目简化为二级缓存）
// private Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>();
```

### 解决流程

```
1. 创建 ServiceA 实例（仅实例化，未填充属性）
   └─ 放入二级缓存 earlySingletonObjects

2. 填充 ServiceA 的属性，发现需要 ServiceB
   └─ 开始创建 ServiceB

3. 创建 ServiceB 实例
   └─ 放入二级缓存

4. 填充 ServiceB 的属性，发现需要 ServiceA
   └─ 从二级缓存获取 ServiceA 的早期引用（半成品）
   └─ ServiceB 创建完成，放入一级缓存

5. ServiceA 继续完成属性填充和初始化
   └─ ServiceA 创建完成，放入一级缓存
```

### 为什么需要三级缓存？

**一级缓存（singletonObjects）**
- 存储完全初始化的 Bean
- 保证单例模式：同一个 Bean 只创建一次

**二级缓存（earlySingletonObjects）**
- 存储早期暴露的 Bean 引用
- 解决循环依赖的关键：允许其他 Bean 获取半成品 Bean

**三级缓存（singletonFactories）**（真实 Spring 有，本项目简化）
- 存储 Bean 工厂（ObjectFactory）
- 用于处理 AOP 代理的循环依赖
- 如果 Bean 需要被代理，从三级缓存获取时会创建代理对象

### 为什么构造器注入无法解决循环依赖？

```java
// 构造器注入
class ServiceA {
    private ServiceB serviceB;
    
    public ServiceA(ServiceB serviceB) {  // 需要先创建 ServiceB
        this.serviceB = serviceB;
    }
}

class ServiceB {
    private ServiceA serviceA;
    
    public ServiceB(ServiceA serviceA) {  // 需要先创建 ServiceA
        this.serviceA = serviceA;
    }
}
```

构造器注入的问题：
- 创建对象时就需要所有依赖
- 无法先创建对象再填充属性
- 无法提前暴露半成品对象

字段注入可以解决：
- 先创建对象（调用无参构造器）
- 将半成品对象放入缓存
- 再填充属性（依赖注入）

---

## 2. Bean 生命周期的 6 个阶段

### 完整流程

```
1. 实例化（Instantiation）
   └─ 通过反射调用构造器创建对象

2. 属性填充（Populate Properties）
   └─ 依赖注入（@Autowired）

3. Aware 接口回调
   └─ BeanNameAware.setBeanName()
   └─ BeanFactoryAware.setBeanFactory()
   └─ ApplicationContextAware.setApplicationContext()

4. BeanPostProcessor 前置处理
   └─ postProcessBeforeInitialization()

5. 初始化（Initialization）
   └─ InitializingBean.afterPropertiesSet()
   └─ 自定义 init-method

6. BeanPostProcessor 后置处理（AOP 代理创建）
   └─ postProcessAfterInitialization()

7. Bean 就绪，可以使用

8. 销毁（Destruction）
   └─ DisposableBean.destroy()
   └─ 自定义 destroy-method
```

### 为什么要分这么多阶段？

#### 1. 实例化与属性填充分离
**设计原因：解决循环依赖**
- 如果在构造器中注入依赖，无法解决循环依赖
- 分离后可以先创建对象，再填充属性

#### 2. Aware 接口回调
**设计原因：让 Bean 感知容器**
- 有些 Bean 需要知道自己的名称
- 有些 Bean 需要手动从容器获取其他 Bean
- 有些 Bean 需要发布事件或访问环境变量

**为什么不在构造器中传入？**
- 这些是可选的，不是所有 Bean 都需要
- 通过接口实现按需获取更灵活

#### 3. BeanPostProcessor 前置处理
**设计原因：初始化前的扩展点**

应用场景：
- `@PostConstruct` 注解的处理
- `@Value` 注解的属性注入
- 验证 Bean 的配置是否正确

#### 4. 初始化回调
**设计原因：Bean 的自定义初始化逻辑**

**为什么不在构造器中做？**
- 构造器执行时，依赖注入还未完成
- 初始化回调执行时，所有依赖都已注入，可以安全使用

应用场景：
- 连接数据库
- 加载配置文件
- 启动定时任务

#### 5. BeanPostProcessor 后置处理
**设计原因：这是 AOP 代理创建的关键时机！**

**为什么在这里创建代理？**
- 此时 Bean 已经完全初始化（实例化、依赖注入、初始化回调都已完成）
- 可以安全地创建代理对象，代理对象可以正确拦截所有方法
- 如果在实例化时创建代理，二级缓存中存的是代理对象，可能导致循环依赖问题

应用场景：
- AOP 代理创建（`@Transactional`、`@Async` 等）
- 动态代理增强

---

## 3. ApplicationContext.refresh() 的 12 个阶段

### 为什么要分 12 个阶段？

1. **职责分离**：每个阶段负责特定的初始化任务
2. **扩展性**：子类可以重写特定阶段来定制行为（模板方法模式）
3. **有序性**：确保初始化步骤按正确顺序执行
4. **可控性**：每个阶段都是明确的检查点

### 关键阶段详解

#### 阶段 5: invokeBeanFactoryPostProcessors()
**这是修改 Bean 定义的最后机会！**

```java
// BeanFactoryPostProcessor 可以修改 Bean 定义
public class CustomBeanFactoryPostProcessor implements BeanFactoryPostProcessor {
    public void postProcessBeanFactory(BeanFactory beanFactory) {
        // 修改 Bean 的作用域
        BeanDefinition bd = beanFactory.getBeanDefinition("userService");
        bd.setScope("prototype");
    }
}
```

**为什么要在实例化 Bean 之前执行？**
- 这里修改的是 Bean 的"配方"（BeanDefinition）
- 必须在"做菜"（实例化）之前改

应用场景：
- `PropertyPlaceholderConfigurer`：替换 `${...}` 占位符
- `@Configuration` 类的解析和注册

#### 阶段 6: registerBeanPostProcessors()
**这是 AOP 实现的关键阶段！**

```java
// BeanPostProcessor 可以在 Bean 初始化前后进行增强
public class AopBeanPostProcessor implements BeanPostProcessor {
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // 如果 Bean 需要被代理，创建代理对象
        if (needsProxy(bean)) {
            return createProxy(bean);
        }
        return bean;
    }
}
```

**为什么 BeanPostProcessor 这么重要？**
- AOP 代理就是通过 BeanPostProcessor 创建的
- `@Autowired`、`@Value` 等注解的处理也是通过 BeanPostProcessor
- 几乎所有的 Bean 增强功能都依赖 BeanPostProcessor

**注意：这里只是注册，真正执行是在阶段 11（实例化 Bean 时）**

#### 阶段 11: finishBeanFactoryInitialization()
**最重要的阶段：实例化所有单例 Bean**

这个阶段会触发：
- Bean 的完整生命周期（实例化、依赖注入、初始化等）
- 循环依赖的解决
- AOP 代理的创建

**为什么要在这个阶段实例化？**
1. 前面的阶段已经完成了所有准备工作（Bean 定义、BeanPostProcessor 等）
2. 此时实例化可以确保所有扩展点都已就绪
3. 提前实例化可以在启动时发现问题，而不是运行时

---

## 4. AOP 动态代理与拦截器链

### JDK 动态代理原理

```java
// 1. 创建代理对象
Object proxy = Proxy.newProxyInstance(
    classLoader,
    interfaces,
    invocationHandler
);

// 2. 调用代理对象的方法
proxy.doSomething();  // 会转发到 InvocationHandler.invoke()

// 3. InvocationHandler 拦截方法调用
public Object invoke(Object proxy, Method method, Object[] args) {
    // 前置逻辑
    Object result = method.invoke(target, args);  // 调用目标方法
    // 后置逻辑
    return result;
}
```

### 拦截器链（责任链模式）

```java
// 多个拦截器按顺序执行
List<MethodInterceptor> interceptors = [
    TransactionInterceptor,
    LoggingInterceptor,
    PerformanceInterceptor
];

// 执行顺序（洋葱模型）
TransactionInterceptor.before
  LoggingInterceptor.before
    PerformanceInterceptor.before
      目标方法执行
    PerformanceInterceptor.after
  LoggingInterceptor.after
TransactionInterceptor.after
```

### 为什么使用责任链模式？

1. **支持多个切面**：事务、日志、性能监控可以同时作用于一个方法
2. **灵活组合**：可以动态添加或移除拦截器
3. **顺序控制**：拦截器按添加顺序执行

### MethodInvocation.proceed() 的设计

```java
public Object proceed() throws Throwable {
    // 所有拦截器都执行完了，调用目标方法
    if (currentInterceptorIndex == interceptors.size() - 1) {
        return method.invoke(target, arguments);
    }
    
    // 执行下一个拦截器
    currentInterceptorIndex++;
    MethodInterceptor interceptor = interceptors.get(currentInterceptorIndex);
    return interceptor.invoke(null, method, arguments, this);
}
```

**关键设计：**
- 每个拦截器内部调用 `invocation.proceed()` 来触发下一个拦截器
- 拦截器可以在 `proceed()` 前后添加逻辑（实现 `@Before`、`@After`）
- 拦截器可以决定是否调用 `proceed()`（实现条件拦截）

---

## 5. 声明式事务的实现原理

### 核心流程

```java
@Transactional
public void createOrder(String username, String product) {
    // 业务逻辑
    userRepository.save(username);
    orderRepository.save(product);
}
```

**底层实现：**

```java
// 1. AOP 拦截带有 @Transactional 的方法
public Object invoke(Method method, Object[] args, MethodInvocation invocation) {
    // 2. 开启事务
    transactionManager.begin();
    
    try {
        // 3. 执行目标方法
        Object result = invocation.proceed();
        
        // 4. 提交事务
        transactionManager.commit();
        return result;
    } catch (Exception e) {
        // 5. 回滚事务
        transactionManager.rollback();
        throw e;
    }
}
```

### 为什么需要声明式事务？

**编程式事务（手动管理）：**
```java
public void createOrder(String username, String product) {
    transactionManager.begin();
    try {
        userRepository.save(username);
        orderRepository.save(product);
        transactionManager.commit();
    } catch (Exception e) {
        transactionManager.rollback();
        throw e;
    }
}
```

**问题：**
- 事务代码与业务代码耦合
- 每个方法都要写重复的事务管理代码
- 容易遗漏提交或回滚

**声明式事务（注解）：**
```java
@Transactional
public void createOrder(String username, String product) {
    userRepository.save(username);
    orderRepository.save(product);
}
```

**优点：**
- 事务逻辑与业务逻辑解耦
- 代码简洁，只需一个注解
- 统一的事务管理，不易出错

### 事务传播机制

```java
@Transactional(propagation = "REQUIRED")
public void methodA() {
    methodB();  // methodB 会加入 methodA 的事务
}

@Transactional(propagation = "REQUIRES_NEW")
public void methodB() {
    // 创建新事务，不受 methodA 影响
}
```

**常见传播级别：**
- `REQUIRED`：如果有事务则加入，没有则创建新事务（默认）
- `REQUIRES_NEW`：总是创建新事务，挂起当前事务
- `NESTED`：嵌套事务，可以独立回滚
- `SUPPORTS`：如果有事务则加入，没有则以非事务方式执行

---

## 总结

### Spring 框架的核心设计思想

1. **分层设计**：每个功能模块职责单一，便于理解和维护
2. **扩展点机制**：通过接口和回调提供扩展能力（BeanPostProcessor、Aware 等）
3. **AOP 思想**：将横切关注点（事务、日志等）与业务逻辑分离
4. **设计模式**：工厂模式、单例模式、代理模式、责任链模式、模板方法模式
5. **解耦**：通过 IoC 容器管理对象依赖，降低耦合度

### 关键技术点

- **三级缓存**：解决循环依赖
- **Bean 生命周期**：提供多个扩展点
- **JDK 动态代理**：实现 AOP
- **责任链模式**：实现拦截器链
- **注解 + 反射**：实现声明式编程

这些设计原理不仅适用于 Spring，也是优秀框架设计的通用思想。
