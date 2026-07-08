# AOP 拦截器执行顺序深度解析

## 核心问题：Around 为什么会打印 2 次？

**答案：因为 Around 通知在 `invocation.proceed()` 前后都有逻辑！**

---

## 三种通知类型的实现对比

### 1. BeforeAdvice（只在前面执行）

```java
public class BeforeAdvice implements MethodInterceptor {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args, 
                         MethodInvocation invocation) throws Throwable {
        // ========== Before 逻辑 ==========
        System.out.println("[AOP-Before] Before method: " + method.getName());
        
        // 直接调用 proceed()，继续执行链
        return invocation.proceed();
        
        // ========== 没有 After 逻辑 ==========
    }
}
```

**特点：**
- 只在 `proceed()` 之前执行逻辑
- `proceed()` 之后直接返回，没有额外逻辑
- **只打印 1 次**

---

### 2. AfterAdvice（只在后面执行）

```java
public class AfterAdvice implements MethodInterceptor {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args, 
                         MethodInvocation invocation) throws Throwable {
        try {
            // ========== 没有 Before 逻辑 ==========
            // 直接调用 proceed()
            return invocation.proceed();
            
        } finally {
            // ========== After 逻辑 ==========
            // 无论是否异常，都会执行
            System.out.println("[AOP-After] After method: " + method.getName());
        }
    }
}
```

**特点：**
- `proceed()` 之前没有逻辑
- 使用 `finally` 确保 `proceed()` 之后一定执行
- **只打印 1 次**

---

### 3. AroundAdvice（前后都执行）⭐

```java
public class AroundAdvice implements MethodInterceptor {
    @Override
    public Object invoke(Object proxy, Method method, Object[] args, 
                         MethodInvocation invocation) throws Throwable {
        // ========== Around Before 逻辑 ==========
        // 第 1 次打印：在 proceed() 之前
        System.out.println("[AOP-Around] Around before: " + method.getName());
        
        try {
            // 调用 proceed()，触发下一个拦截器或目标方法
            Object result = invocation.proceed();
            
            // ========== Around After 逻辑 ==========
            // 第 2 次打印：在 proceed() 之后
            System.out.println("[AOP-Around] Around after: " + method.getName());
            return result;
            
        } catch (Throwable e) {
            // 异常处理逻辑
            System.out.println("[AOP-Around] Exception in: " + method.getName());
            throw e;
        }
    }
}
```

**特点：**
- `proceed()` 之前有逻辑（第 1 次打印）
- `proceed()` 之后有逻辑（第 2 次打印）
- **打印 2 次**

---

## 完整执行流程分析

### 添加顺序
```java
aopProxy.addInterceptor(new BeforeAdvice("LoggingAspect"));      // 索引 0
aopProxy.addInterceptor(new AroundAdvice("PerformanceAspect"));  // 索引 1
aopProxy.addInterceptor(new AfterAdvice("CleanupAspect"));       // 索引 2
```

### 执行流程（调用 save() 方法）

```java
// ========== 开始执行 ==========
代理对象.save()
  ↓
JdkDynamicAopProxy.invoke() 拦截
  ↓
创建 ReflectiveMethodInvocation(interceptors=[Before, Around, After])
  ↓
invocation.proceed()  // 第 1 次调用，index: -1 → 0

// ========== 拦截器 0: BeforeAdvice ==========
BeforeAdvice.invoke() {
    System.out.println("[AOP-Before] Before method: save");  // ← 打印 1
    return invocation.proceed();  // 第 2 次调用，index: 0 → 1
    
    // ========== 拦截器 1: AroundAdvice ==========
    AroundAdvice.invoke() {
        // Around Before 逻辑
        System.out.println("[AOP-Around] Around before: save");  // ← 打印 2
        
        try {
            Object result = invocation.proceed();  // 第 3 次调用，index: 1 → 2
            
            // ========== 拦截器 2: AfterAdvice ==========
            AfterAdvice.invoke() {
                try {
                    return invocation.proceed();  // 第 4 次调用，index: 2 → 3
                    
                    // ========== 目标方法 ==========
                    // index == 2 == interceptors.size() - 1
                    // 执行目标方法
                    method.invoke(target, args) {
                        System.out.println("[UserRepository] Saved: Alice");  // ← 打印 3
                        return null;
                    }
                    // 目标方法返回，开始回溯
                    
                } finally {
                    // After 逻辑
                    System.out.println("[AOP-After] After method: save");  // ← 打印 4
                }
            }
            // AfterAdvice 返回
            
            // Around After 逻辑
            System.out.println("[AOP-Around] Around after: save");  // ← 打印 5
            return result;
            
        } catch (Throwable e) {
            // 异常处理
        }
    }
    // AroundAdvice 返回
}
// BeforeAdvice 返回

// ========== 执行完成 ==========
```

---

## 为什么是这个顺序？关键在于 proceed()

### proceed() 的作用

```java
public Object proceed() throws Throwable {
    // 如果还有下一个拦截器，执行下一个
    if (currentInterceptorIndex < interceptors.size() - 1) {
        currentInterceptorIndex++;
        MethodInterceptor interceptor = interceptors.get(currentInterceptorIndex);
        return interceptor.invoke(null, method, arguments, this);
    }
    
    // 所有拦截器都执行完了，调用目标方法
    return method.invoke(target, arguments);
}
```

**关键点：**
- `proceed()` 是一个**递归调用**
- 每次调用 `proceed()` 都会触发下一个拦截器
- 当所有拦截器都执行完，才会调用目标方法
- 目标方法返回后，**递归回溯**，执行每个拦截器的 after 逻辑

---

## 调用栈分析

```
调用栈（从上到下）：

1. BeforeAdvice.invoke()
   ├─ before 逻辑执行                    ← 打印 "[AOP-Before]"
   └─ invocation.proceed()               ← 调用下一个
       ↓
2. AroundAdvice.invoke()
   ├─ before 逻辑执行                    ← 打印 "[AOP-Around] before"
   └─ invocation.proceed()               ← 调用下一个
       ↓
3. AfterAdvice.invoke()
   └─ invocation.proceed()               ← 调用下一个
       ↓
4. 目标方法.save()                       ← 打印 "[UserRepository]"
   └─ 返回                               ← 开始回溯
       ↑
3. AfterAdvice.invoke()
   └─ finally 块执行                     ← 打印 "[AOP-After]"
       ↑
2. AroundAdvice.invoke()
   └─ after 逻辑执行                     ← 打印 "[AOP-Around] after"
       ↑
1. BeforeAdvice.invoke()
   └─ 返回（没有 after 逻辑）
```

---

## 实际输出验证

```
[AOP-Before] LoggingAspect - Before method: save           ← BeforeAdvice: before
[AOP-Around] PerformanceAspect - Around before: save       ← AroundAdvice: before (第 1 次)
        [UserRepository] Saved to database: Alice          ← 目标方法
[AOP-After] CleanupAspect - After method: save             ← AfterAdvice: after
[AOP-Around] PerformanceAspect - Around after: save        ← AroundAdvice: after (第 2 次)
```

---

## 为什么 Around 打印 2 次？

### 代码结构决定的

```java
public Object invoke(..., MethodInvocation invocation) throws Throwable {
    // ========== 第 1 次打印 ==========
    System.out.println("Around before");  // proceed() 之前
    
    Object result = invocation.proceed();  // 这里会阻塞，等待后续拦截器和目标方法执行完
    
    // ========== 第 2 次打印 ==========
    System.out.println("Around after");   // proceed() 之后
    
    return result;
}
```

**关键点：**
1. `invocation.proceed()` 是一个**阻塞调用**
2. 它会等待所有后续拦截器和目标方法执行完才返回
3. 返回后，继续执行 `proceed()` 之后的代码
4. 所以 Around 的 before 和 after 逻辑都会执行

---

## 对比：Before 为什么只打印 1 次？

```java
public Object invoke(..., MethodInvocation invocation) throws Throwable {
    // ========== 唯一的打印 ==========
    System.out.println("Before");
    
    // 直接返回 proceed() 的结果，没有后续逻辑
    return invocation.proceed();
}
```

**关键点：**
- `proceed()` 之后直接返回，没有额外代码
- 所以只打印 1 次

---

## 对比：After 为什么只打印 1 次？

```java
public Object invoke(..., MethodInvocation invocation) throws Throwable {
    try {
        // 没有 before 逻辑，直接调用 proceed()
        return invocation.proceed();
    } finally {
        // ========== 唯一的打印 ==========
        System.out.println("After");
    }
}
```

**关键点：**
- `proceed()` 之前没有逻辑
- 只在 `finally` 块中打印 1 次

---

## 洋葱模型图解

```
添加顺序：Before → Around → After

执行模型（洋葱模型）：

    ┌─────────────────────────────────────┐
    │ BeforeAdvice                        │
    │  ┌─ before 逻辑 ──────────────┐    │
    │  │                             │    │
    │  │  ┌─────────────────────────┐│   │
    │  │  │ AroundAdvice            ││   │
    │  │  │  ┌─ before 逻辑 ───┐   ││   │
    │  │  │  │                  │   ││   │
    │  │  │  │  ┌──────────────┐│  ││   │
    │  │  │  │  │ AfterAdvice  ││  ││   │
    │  │  │  │  │              ││  ││   │
    │  │  │  │  │  ┌────────┐ ││  ││   │
    │  │  │  │  │  │ 目标方法│ ││  ││   │
    │  │  │  │  │  └────────┘ ││  ││   │
    │  │  │  │  │              ││  ││   │
    │  │  │  │  │  after 逻辑  ││  ││   │
    │  │  │  │  └──────────────┘│  ││   │
    │  │  │  │                  │   ││   │
    │  │  │  └─ after 逻辑 ────┘   ││   │
    │  │  └─────────────────────────┘│   │
    │  │                             │    │
    │  └─────────────────────────────┘    │
    └─────────────────────────────────────┘

执行顺序：
1. BeforeAdvice: before
2. AroundAdvice: before  ← 第 1 次打印
3. AfterAdvice: (无 before)
4. 目标方法
5. AfterAdvice: after
6. AroundAdvice: after   ← 第 2 次打印
7. BeforeAdvice: (无 after)
```

---

## 总结

| 通知类型 | proceed() 之前 | proceed() 之后 | 打印次数 |
|---------|---------------|---------------|---------|
| **BeforeAdvice** | ✅ 有逻辑 | ❌ 无逻辑 | 1 次 |
| **AfterAdvice** | ❌ 无逻辑 | ✅ 有逻辑 | 1 次 |
| **AroundAdvice** | ✅ 有逻辑 | ✅ 有逻辑 | **2 次** |

**核心原理：**
1. `invocation.proceed()` 是一个阻塞调用
2. 它会等待所有后续拦截器和目标方法执行完才返回
3. Around 在 `proceed()` 前后都有逻辑，所以打印 2 次
4. Before 只在 `proceed()` 之前有逻辑，所以打印 1 次
5. After 只在 `proceed()` 之后有逻辑，所以打印 1 次

**执行顺序由代码结构决定，不是由拦截器类型决定！**
