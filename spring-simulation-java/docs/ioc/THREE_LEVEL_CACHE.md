# Spring 三级缓存详解

## 概述

Spring 使用三级缓存机制来解决单例 Bean 的循环依赖问题。这是 Spring IoC 容器最精妙的设计之一。

## 三级缓存结构

### 一级缓存：singletonObjects
```java
private Map<String, Object> singletonObjects = new HashMap<>();
```

**作用**: 存放完全初始化完成的单例 Bean

**特点**:
- 这是最终的 Bean 存储位置
- Bean 已经完成了完整的生命周期（实例化、属性填充、初始化）
- 可以直接使用

### 二级缓存：earlySingletonObjects
```java
private Map<String, Object> earlySingletonObjects = new HashMap<>();
```

**作用**: 存放早期暴露的 Bean 引用（已实例化，未完成初始化）

**特点**:
- 缓存从三级缓存中获取的早期 Bean 引用
- 避免重复调用工厂方法
- 可能是原始对象，也可能是代理对象

### 三级缓存：singletonFactories
```java
private Map<String, Supplier<Object>> singletonFactories = new HashMap<>();
```

**作用**: 存放 Bean 工厂（ObjectFactory）

**特点**:
- 存储的是一个函数式接口（Supplier）
- 只有在发生循环依赖时才会被调用
- 支持延迟创建代理对象

## 为什么需要三级缓存？

### 问题：为什么不能只用两级缓存？

如果只用两级缓存（一级存完整 Bean，二级存早期引用），会有以下问题：

1. **无法延迟代理对象的创建**
   - 必须在实例化后立即决定是否创建代理
   - 即使没有循环依赖也要创建代理，浪费资源

2. **代理对象的唯一性问题**
   - 如果多个 Bean 依赖同一个 Bean，可能创建多个代理对象
   - 无法保证返回同一个代理实例

### 三级缓存的优势

1. **延迟代理对象的创建**
   ```java
   // 只有在真正发生循环依赖时才调用工厂方法
   Supplier<Object> factory = () -> getEarlyBeanReference(beanName, bean);
   singletonFactories.put(beanName, factory);
   ```

2. **保证代理对象的唯一性**
   ```java
   // 第一次调用工厂方法后，将结果放入二级缓存
   Object earlyBean = factory.get();
   earlySingletonObjects.put(beanName, earlyBean);
   singletonFactories.remove(beanName);
   ```

3. **支持 AOP**
   ```java
   private Object getEarlyBeanReference(String beanName, Object bean) {
       // 如果 Bean 需要被代理，这里返回代理对象
       // 否则返回原始对象
       return bean; // 简化实现
   }
   ```

## 循环依赖解决流程

### 场景：ServiceA 依赖 ServiceB，ServiceB 依赖 ServiceA

```
时间线：

T1: 开始创建 ServiceA
    - 实例化 ServiceA
    - 将 ServiceA 的工厂方法放入 L3（singletonFactories）
    - 标记 ServiceA 正在创建中

T2: 填充 ServiceA 的属性
    - 发现需要 ServiceB
    - 开始创建 ServiceB

T3: 开始创建 ServiceB
    - 实例化 ServiceB
    - 将 ServiceB 的工厂方法放入 L3
    - 标记 ServiceB 正在创建中

T4: 填充 ServiceB 的属性
    - 发现需要 ServiceA
    - 调用 getBean("serviceA")

T5: 获取 ServiceA（循环依赖发生）
    - L1 查找：未找到（ServiceA 还未完成初始化）
    - L2 查找：未找到（ServiceA 还未被早期暴露）
    - L3 查找：找到 ServiceA 的工厂方法
    - 调用工厂方法获取 ServiceA 的早期引用
    - 将早期引用放入 L2
    - 从 L3 移除 ServiceA 的工厂方法
    - 返回 ServiceA 的早期引用给 ServiceB

T6: ServiceB 完成初始化
    - 属性填充完成（已注入 ServiceA）
    - 执行初始化回调
    - 放入 L1（singletonObjects）
    - 从 L2 和 L3 移除
    - 移除创建中标记

T7: ServiceA 继续初始化
    - 属性填充完成（已注入 ServiceB）
    - 执行初始化回调
    - 放入 L1
    - 从 L2 移除（L3 已在 T5 移除）
    - 移除创建中标记

T8: 完成
    - ServiceA 和 ServiceB 都在 L1 中
    - 循环依赖已解决
```

## 代码实现

### 获取单例 Bean

```java
public Object getBean(String beanName) throws Exception {
    BeanDefinition beanDefinition = beanDefinitions.get(beanName);
    
    if (beanDefinition.isSingleton()) {
        // 尝试从三级缓存中获取
        Object singleton = getSingleton(beanName);
        if (singleton != null) {
            return singleton;
        }
        
        // 创建新的 Bean
        return getSingleton(beanName, () -> createBean(beanName, beanDefinition));
    }
    
    // Prototype 作用域
    return createBean(beanName, beanDefinition);
}
```

### 从三级缓存获取 Bean

```java
private Object getSingleton(String beanName) {
    // L1: 一级缓存
    Object bean = singletonObjects.get(beanName);
    if (bean != null) {
        return bean;
    }
    
    // 只有在 Bean 正在创建中时才查找 L2 和 L3
    if (singletonsCurrentlyInCreation.containsKey(beanName)) {
        // L2: 二级缓存
        bean = earlySingletonObjects.get(beanName);
        if (bean != null) {
            return bean;
        }
        
        // L3: 三级缓存
        Supplier<Object> factory = singletonFactories.get(beanName);
        if (factory != null) {
            // 调用工厂方法获取早期引用
            bean = factory.get();
            // 放入 L2
            earlySingletonObjects.put(beanName, bean);
            // 从 L3 移除
            singletonFactories.remove(beanName);
            return bean;
        }
    }
    
    return null;
}
```

### 创建 Bean 并添加到三级缓存

```java
private Object createBean(String beanName, BeanDefinition beanDefinition) {
    // 1. 实例化
    Object bean = beanDefinition.getBeanClass().newInstance();
    
    // 2. 添加到三级缓存（关键步骤）
    if (beanDefinition.isSingleton()) {
        addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, bean));
    }
    
    // 3. 属性填充（可能触发循环依赖）
    populateBean(bean);
    
    // 4. 初始化
    initializeBean(bean, beanName);
    
    return bean;
}
```

### 添加到三级缓存

```java
private void addSingletonFactory(String beanName, Supplier<Object> factory) {
    if (!singletonObjects.containsKey(beanName)) {
        singletonFactories.put(beanName, factory);
        earlySingletonObjects.remove(beanName);
    }
}
```

### 获取早期 Bean 引用

```java
private Object getEarlyBeanReference(String beanName, Object bean) {
    Object exposedObject = bean;
    
    // 这里可以应用 BeanPostProcessor 来创建代理对象
    // 例如：如果 Bean 有 @Transactional 注解，这里会创建事务代理
    // 真实 Spring 会调用 SmartInstantiationAwareBeanPostProcessor.getEarlyBeanReference()
    
    return exposedObject;
}
```

## 测试验证

运行 `SpringAdvancedDemo` 可以看到三级缓存的工作过程：

```bash
java -cp bin com.spring.simulation.examples.context.SpringAdvancedDemo
```

输出示例：
```
[Bean Lifecycle] ========== Creating Bean: serviceA ==========
[Bean Lifecycle] 1. Instantiate bean
   [L3 Cache] Added singleton factory for: serviceA
[Bean Lifecycle] 2. Populate properties (dependency injection)

[Bean Lifecycle] ========== Creating Bean: serviceB ==========
[Bean Lifecycle] 1. Instantiate bean
   [L3 Cache] Added singleton factory for: serviceB
[Bean Lifecycle] 2. Populate properties (dependency injection)
[IoC] Getting early singleton from L3 factory (resolving circular dependency): serviceA
   [Early Reference] Exposing early reference for: serviceA
   [Autowired] Injected serviceA into ServiceB
[Bean Lifecycle] 3. Invoke Aware interfaces
...
[Bean Lifecycle] Bean creation completed: serviceB

   [Autowired] Injected serviceB into ServiceA
...
[Bean Lifecycle] Bean creation completed: serviceA
```

## 关键要点

1. **三级缓存是必需的**
   - 不是为了性能优化
   - 是为了支持 AOP 和延迟代理创建

2. **只有单例 Bean 才使用三级缓存**
   - Prototype 作用域不支持循环依赖
   - 每次都创建新实例，无法缓存

3. **构造器注入无法解决循环依赖**
   - 构造器注入在实例化时就需要依赖
   - 此时还没有放入三级缓存
   - 只有字段注入和 setter 注入可以解决

4. **代理对象的创建时机**
   - 正常情况：在 BeanPostProcessor 后置处理时创建
   - 循环依赖：在获取早期引用时创建（通过工厂方法）

## 与真实 Spring 的对比

### 已实现
- ✅ 完整的三级缓存结构
- ✅ 循环依赖检测和解决
- ✅ 早期引用暴露机制
- ✅ Bean 工厂延迟调用

### 简化说明
- 简化了 `getEarlyBeanReference` 的实现（真实 Spring 会调用所有 SmartInstantiationAwareBeanPostProcessor）
- 简化了代理对象的创建逻辑
- 未实现完整的循环依赖异常处理

## 总结

Spring 的三级缓存是一个精妙的设计，它不仅解决了循环依赖问题，还支持了 AOP 代理的延迟创建。理解三级缓存的工作原理，是深入理解 Spring IoC 容器的关键。

**核心思想**：
- 一级缓存：存最终结果
- 二级缓存：缓存中间结果
- 三级缓存：延迟决策（是否需要代理）
