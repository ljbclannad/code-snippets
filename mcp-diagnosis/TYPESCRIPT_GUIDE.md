# TypeScript 学习指南 - 通过 MCP 诊断工具学习

这个文档将带你深入理解项目中用到的 TypeScript 核心概念。

## 📚 目录

1. [类型系统基础](#1-类型系统基础)
2. [接口与类型别名](#2-接口与类型别名)
3. [枚举类型](#3-枚举类型)
4. [泛型编程](#4-泛型编程)
5. [异步编程](#5-异步编程)
6. [模块系统](#6-模块系统)
7. [类与面向对象](#7-类与面向对象)
8. [高级类型特性](#8-高级类型特性)

---

## 1. 类型系统基础

### 1.1 基本类型注解

```typescript
// 查看 src/types.ts:25-30
const name: string = 'Gateway';
const port: number = 8080;
const isHealthy: boolean = true;
const data: any = { status: 'ok' };  // any 类型绕过类型检查
```

**学习要点：**
- TypeScript 是 JavaScript 的超集，添加了类型系统
- 类型注解使用 `:` 符号
- 基本类型：`string`, `number`, `boolean`, `any`, `void`, `null`, `undefined`

### 1.2 数组类型

```typescript
// 两种写法等价
const ports1: number[] = [8080, 8081, 8082];
const ports2: Array<number> = [8080, 8081, 8082];
```

---

## 2. 接口与类型别名

### 2.1 接口（Interface）

接口用于定义对象的"形状"（shape）。

```typescript
// 查看 src/types.ts:35-42
export interface ServiceInfo {
  name: string;              // 必需属性
  type: ServiceType;
  host: string;
  port: number;
  healthEndpoint?: string;   // 可选属性（用 ? 标记）
  managementPort?: number;
}
```

**学习要点：**
- `interface` 关键字定义接口
- `?` 表示可选属性
- TypeScript 会检查对象是否符合接口定义

**实际应用：**
```typescript
// ✅ 正确
const service: ServiceInfo = {
  name: 'Gateway',
  type: ServiceType.GATEWAY,
  host: 'localhost',
  port: 8080
};

// ❌ 错误：缺少必需属性 'port'
const badService: ServiceInfo = {
  name: 'Gateway',
  type: ServiceType.GATEWAY,
  host: 'localhost'
};
```

### 2.2 类型别名（Type Alias）

```typescript
// 查看 src/types.ts:68-72
export type DatabaseType = 'mysql' | 'redis';  // 联合类型
export type CheckerFunction<T> = () => Promise<T>;  // 泛型类型
```

**Interface vs Type 的选择：**
- **Interface**：用于定义对象结构，可扩展（extends）
- **Type**：更灵活，可用于联合类型、交叉类型、映射类型等

---

## 3. 枚举类型

枚举用于定义一组命名常量。

```typescript
// 查看 src/types.ts:14-22
export enum HealthStatus {
  HEALTHY = 'healthy',
  UNHEALTHY = 'unhealthy',
  DEGRADED = 'degraded',
  UNKNOWN = 'unknown'
}

// 使用枚举
const status: HealthStatus = HealthStatus.HEALTHY;

// ✅ 类型安全
if (status === HealthStatus.HEALTHY) {
  console.log('服务健康');
}

// ❌ 错误：不能直接使用字符串
// const badStatus: HealthStatus = 'healthy';  // 编译错误
```

**学习要点：**
- 枚举提供类型安全和代码可读性
- 字符串枚举 vs 数字枚举
- 使用枚举值而不是魔法字符串

---

## 4. 泛型编程

泛型使代码更加灵活和可复用。

### 4.1 泛型函数

```typescript
// 查看 src/types.ts:115-117
type CheckerFunction<T> = () => Promise<T>;

// 使用示例
const numberChecker: CheckerFunction<number> = async () => 42;
const stringChecker: CheckerFunction<string> = async () => 'OK';
```

### 4.2 泛型接口

```typescript
// Promise 是内置的泛型类型
async function fetchData(): Promise<ServiceInfo> {
  // 返回 ServiceInfo 类型的数据
  return { /* ... */ };
}
```

**学习要点：**
- `<T>` 是类型参数，代表"任意类型"
- 泛型在调用时确定具体类型
- 常见泛型：`Promise<T>`, `Array<T>`, `Record<K, V>`

---

## 5. 异步编程

### 5.1 Promise 类型

```typescript
// 查看 src/checkers/service-checker.ts:25-35
export async function checkServiceHealth(
  service: ServiceInfo
): Promise<HealthCheckResult> {
  // async 函数总是返回 Promise
  const response = await axios.get(url);
  return {
    service: service.name,
    status: HealthStatus.HEALTHY,
    // ...
  };
}
```

**学习要点：**
- `async` 函数返回 `Promise<T>`
- `await` 等待 Promise 完成
- TypeScript 会推断 Promise 的返回类型

### 5.2 Promise.all 并行执行

```typescript
// 查看 src/checkers/service-checker.ts:106-115
export async function checkMultipleServices(
  services: ServiceInfo[]
): Promise<HealthCheckResult[]> {
  // map 返回 Promise 数组
  const checks = services.map(service => checkServiceHealth(service));
  
  // Promise.all 并行执行，返回结果数组
  const results = await Promise.all(checks);
  return results;
}
```

---

## 6. 模块系统

### 6.1 导入导出

```typescript
// 导出类型（查看 src/types.ts）
export interface ServiceInfo { /* ... */ }
export enum HealthStatus { /* ... */ }

// 导入类型（查看 src/checkers/service-checker.ts:11）
import type { ServiceInfo, HealthCheckResult } from '../types.js';
import { HealthStatus } from '../types.js';
```

**学习要点：**
- `import type` 只导入类型，编译后会被移除
- `.js` 扩展名：Node16 模块解析需要
- 类型和值可以分开导入

### 6.2 重新导出

```typescript
// 查看 src/checkers/index.ts
export * from './service-checker.js';
export * from './database-checker.js';

// 使用方可以从一个入口导入所有内容
import { checkServiceHealth, checkDatabaseHealth } from './checkers/index.js';
```

---

## 7. 类与面向对象

### 7.1 类的基本结构

```typescript
// 查看 src/diagnosis-service.ts:30-50
export class DiagnosisService {
  // 私有成员变量
  private config: DiagnosisConfig;

  // 构造函数
  constructor(config: DiagnosisConfig) {
    this.config = config;
  }

  // 公共方法
  public async runDiagnosis(): Promise<DiagnosisReport> {
    // 使用 this 访问成员变量
    const results = await checkMultipleServices(this.config.services);
    return { /* ... */ };
  }

  // 私有方法
  private generateSummary(results: HealthCheckResult[]) {
    // ...
  }
}
```

**学习要点：**
- `private` 成员只能在类内部访问
- `public` 成员可以从外部访问（默认）
- `constructor` 用于初始化对象
- `this` 指向当前实例

### 7.2 类的使用

```typescript
// 创建实例
const service = new DiagnosisService(config);

// 调用方法
const report = await service.runDiagnosis();
```

---

## 8. 高级类型特性

### 8.1 联合类型

```typescript
// 查看 src/types.ts:68
export interface DatabaseConfig {
  type: 'mysql' | 'redis';  // 只能是这两个值之一
  host: string;
  port: number;
}
```

### 8.2 可选属性与 undefined

```typescript
// ? 表示可选
interface Config {
  timeout?: number;  // 类型是 number | undefined
}

// 使用可选链操作符
const timeout = config.timeout ?? 5000;  // 如果是 undefined，使用 5000
```

### 8.3 Record 类型

```typescript
// Record<K, V> 表示键值对对象
type ConfigMap = Record<string, any>;

const config: ConfigMap = {
  host: 'localhost',
  port: 8080,
  timeout: 5000
};
```

### 8.4 类型守卫

```typescript
// 查看 src/checkers/service-checker.ts:80-92
if (axios.isAxiosError(error)) {
  // 在这个分支中，TypeScript 知道 error 是 AxiosError 类型
  const axiosError = error as AxiosError;
  errorMessage = axiosError.message;
} else if (error instanceof Error) {
  // 在这个分支中，error 是 Error 类型
  errorMessage = error.message;
}
```

**学习要点：**
- 类型守卫缩窄类型范围
- `instanceof` 检查对象类型
- `as` 进行类型断言

### 8.5 never 类型

```typescript
// 查看 src/checkers/database-checker.ts:95-102
function checkDatabaseHealth(config: DatabaseConfig) {
  switch (config.type) {
    case 'mysql':
      return checkMySQLConnection(config);
    case 'redis':
      return checkRedisConnection(config);
    default:
      // never 类型：不应该到达这里
      const _exhaustiveCheck: never = config.type;
      throw new Error(`Unsupported type: ${_exhaustiveCheck}`);
  }
}
```

**学习要点：**
- `never` 表示永远不会发生的类型
- 用于穷尽性检查（exhaustiveness checking）
- 如果遗漏了某个 case，TypeScript 会报错

---

## 🎯 实践建议

### 1. 阅读顺序

1. **types.ts** - 理解类型系统和数据结构
2. **service-checker.ts** - 学习异步编程和错误处理
3. **database-checker.ts** - 理解条件类型和类型守卫
4. **diagnosis-service.ts** - 学习类和面向对象
5. **index.ts** - 理解 MCP 集成和实际应用

### 2. 动手练习

#### 练习 1：添加新的服务类型

在 `types.ts` 中添加新的服务类型：

```typescript
export enum ServiceType {
  // ... 现有的
  NOTIFICATION = 'notification',  // 添加通知服务
}
```

#### 练习 2：实现 Redis 检查器

完善 `database-checker.ts` 中的 Redis 检查功能：

```bash
npm install redis
npm install --save-dev @types/redis
```

```typescript
import { createClient } from 'redis';

export async function checkRedisConnection(
  config: DatabaseConfig
): Promise<DatabaseHealthResult> {
  const startTime = Date.now();
  
  try {
    const client = createClient({
      socket: {
        host: config.host,
        port: config.port
      }
    });
    
    await client.connect();
    await client.ping();
    await client.disconnect();
    
    return {
      type: 'Redis',
      status: HealthStatus.HEALTHY,
      connectionTime: Date.now() - startTime
    };
  } catch (error) {
    return {
      type: 'Redis',
      status: HealthStatus.UNHEALTHY,
      connectionTime: Date.now() - startTime,
      error: error instanceof Error ? error.message : 'Unknown error'
    };
  }
}
```

#### 练习 3：添加重试机制

为服务检查添加重试逻辑：

```typescript
async function checkServiceHealthWithRetry(
  service: ServiceInfo,
  maxRetries: number = 3
): Promise<HealthCheckResult> {
  let lastError: Error | undefined;
  
  for (let i = 0; i < maxRetries; i++) {
    try {
      return await checkServiceHealth(service);
    } catch (error) {
      lastError = error instanceof Error ? error : new Error('Unknown');
      if (i < maxRetries - 1) {
        await new Promise(resolve => setTimeout(resolve, 1000));
      }
    }
  }
  
  throw lastError!;
}
```

### 3. 调试技巧

```typescript
// 使用类型断言查看推断的类型
const result = await checkServiceHealth(service);
// 鼠标悬停在 result 上可以看到完整类型

// 使用 satisfies 确保类型正确
const config = {
  name: 'Gateway',
  type: ServiceType.GATEWAY,
  host: 'localhost',
  port: 8080
} satisfies ServiceInfo;
```

---

## 📚 推荐资源

1. **TypeScript 官方文档**
   - Handbook: https://www.typescriptlang.org/docs/handbook/intro.html
   - 每日小贴士: https://www.typescriptlang.org/docs/handbook/typescript-in-5-minutes.html

2. **实用工具**
   - TypeScript Playground: https://www.typescriptlang.org/play
   - 在线测试 TypeScript 代码

3. **进阶学习**
   - Type Challenges: https://github.com/type-challenges/type-challenges
   - 通过挑战学习高级类型

---

## ❓ 常见问题

### Q1: 什么时候使用 interface，什么时候使用 type？

**A:** 
- 定义对象结构 → 使用 `interface`
- 联合类型、交叉类型 → 使用 `type`
- 需要扩展 → 使用 `interface`（支持 extends）

### Q2: any vs unknown 有什么区别？

**A:**
- `any` 完全绕过类型检查，不安全
- `unknown` 需要类型守卫才能使用，更安全

```typescript
let value: unknown = 'hello';
// value.toUpperCase();  // ❌ 错误
if (typeof value === 'string') {
  value.toUpperCase();  // ✅ 正确
}
```

### Q3: 为什么要用 .js 扩展名导入？

**A:** 
使用 `"module": "Node16"` 时，TypeScript 要求显式指定扩展名，这是 ES 模块标准的要求。

### Q4: 如何调试类型错误？

**A:**
1. 使用 IDE 的类型提示（鼠标悬停）
2. 使用 `type` 关键字创建中间类型查看
3. 使用 TypeScript Playground 隔离问题

---

## 🎓 下一步

学完这些内容后，你可以：

1. ✅ 为项目添加新功能（如监控指标收集）
2. ✅ 实现更复杂的检查器（如 Nacos 注册中心检查）
3. ✅ 优化类型定义（使用泛型提高复用性）
4. ✅ 学习更高级的 TypeScript 特性（装饰器、条件类型等）

祝学习愉快！🚀
