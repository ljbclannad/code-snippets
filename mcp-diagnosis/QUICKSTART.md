# 🚀 快速开始指南

## 项目概述

这是一个用于诊断 **governance-suite** 分布式微服务系统健康状态的 MCP (Model Context Protocol) 服务器。通过这个项目，你可以：

- ✅ 学习 TypeScript 核心概念
- ✅ 理解 MCP 协议的使用
- ✅ 实现实用的服务健康诊断工具

## 📁 项目结构

```
mcp-diagnosis/
├── src/                          # 源代码目录
│   ├── index.ts                  # MCP 服务器入口（200+ 行）
│   ├── types.ts                  # 类型定义（接口、枚举、类型别名）
│   ├── diagnosis-service.ts      # 诊断服务核心类
│   └── checkers/                 # 检查器模块
│       ├── index.ts              # 统一导出
│       ├── service-checker.ts    # 服务健康检查
│       └── database-checker.ts   # 数据库连接检查
├── build/                        # 编译输出目录
├── package.json                  # 项目配置和依赖
├── tsconfig.json                 # TypeScript 配置
├── README.md                     # 项目说明
├── TYPESCRIPT_GUIDE.md          # TypeScript 详细学习指南
├── configure.sh                  # MCP 配置生成脚本
└── config.example.json          # 配置示例
```

## 🎯 核心功能

### 1. 服务健康检查
- 检查微服务是否在线
- 测量响应时间
- 支持自定义健康检查端点

### 2. 数据库连接检查
- MySQL 连接测试
- Redis 连接测试（可扩展）
- 连接时间统计

### 3. 统计报告
- 生成格式化的诊断报告
- 健康状态分类统计
- 详细错误信息

## ⚡ 快速开始

### 步骤 1: 查看已编译的代码

项目已经编译完成，查看构建结果：

```bash
ls -la build/
```

### 步骤 2: 配置 Claude Desktop

运行配置脚本：

```bash
./configure.sh
```

按照输出的说明，将配置添加到 Claude Desktop。

### 步骤 3: 重启 Claude Desktop

配置完成后重启 Claude Desktop，MCP 服务器会自动启动。

### 步骤 4: 在 Claude 中使用

在 Claude 对话中输入：

```
使用 quick_diagnose 工具检查本地环境的服务状态
```

或者自定义检查：

```
使用 diagnose_services 工具检查以下服务：
1. Gateway: localhost:8080
2. Auth Service: localhost:8081
3. User Service: localhost:8082
```

## 🛠️ 开发命令

```bash
# 重新编译
npm run build

# 启动 MCP 服务器（调试模式）
npm start

# 编译并启动
npm run dev

# 清理构建文件
npm run clean
```

## 📖 学习路径

### 新手路径（推荐顺序）

1. **了解项目结构**
   - 阅读 [README.md](README.md)
   - 查看文件组织方式

2. **学习 TypeScript 基础**
   - 阅读 [TYPESCRIPT_GUIDE.md](TYPESCRIPT_GUIDE.md)
   - 从类型系统开始

3. **阅读源代码**
   - `types.ts` - 数据结构和类型定义
   - `service-checker.ts` - 异步编程示例
   - `database-checker.ts` - 错误处理示例
   - `diagnosis-service.ts` - 面向对象设计
   - `index.ts` - MCP 集成

4. **动手实践**
   - 添加新的服务类型
   - 实现 Redis 检查器
   - 添加重试机制
   - 实现超时控制

### 进阶路径

1. **扩展功能**
   - 添加 Nacos 注册中心检查
   - 实现服务依赖图分析
   - 添加性能指标收集
   - 实现告警阈值配置

2. **优化代码**
   - 使用泛型优化检查器
   - 实现插件化架构
   - 添加缓存机制
   - 实现并发控制

3. **集成测试**
   - 编写单元测试
   - 实现集成测试
   - 添加性能测试

## 🎓 TypeScript 学习要点

### 项目中涵盖的核心概念

| 概念 | 位置 | 说明 |
|------|------|------|
| 接口 (Interface) | `types.ts:35-42` | 定义对象结构 |
| 枚举 (Enum) | `types.ts:14-22` | 命名常量集合 |
| 类型别名 (Type) | `types.ts:115-117` | 灵活的类型定义 |
| 泛型 (Generics) | `types.ts:115` | 类型参数化 |
| 异步编程 | `service-checker.ts:25` | Promise 和 async/await |
| 类 (Class) | `diagnosis-service.ts:30` | 面向对象编程 |
| 模块系统 | `checkers/index.ts` | import/export |
| 类型守卫 | `service-checker.ts:80` | 类型缩窄 |

### 关键代码片段解析

#### 1. 类型安全的枚举

```typescript
// types.ts
export enum HealthStatus {
  HEALTHY = 'healthy',
  UNHEALTHY = 'unhealthy',
  DEGRADED = 'degraded',
  UNKNOWN = 'unknown'
}

// 使用时享受类型安全
const status: HealthStatus = HealthStatus.HEALTHY;  // ✅
// const bad: HealthStatus = 'healthy';  // ❌ 编译错误
```

#### 2. 异步函数的类型注解

```typescript
// service-checker.ts
export async function checkServiceHealth(
  service: ServiceInfo
): Promise<HealthCheckResult> {
  // async 函数自动返回 Promise
  const response = await axios.get(url);
  return { /* ... */ };
}
```

#### 3. 类的访问修饰符

```typescript
// diagnosis-service.ts
export class DiagnosisService {
  private config: DiagnosisConfig;  // 私有成员
  
  public async runDiagnosis(): Promise<DiagnosisReport> {
    // 可以访问 private 成员
    const results = await checkMultipleServices(this.config.services);
    return { /* ... */ };
  }
}
```

## 🔧 常见问题

### Q: 如何调试 MCP 服务器？

A: 查看 Claude Desktop 的日志：

```bash
# macOS
tail -f ~/Library/Logs/Claude/mcp*.log

# 或者直接运行服务器查看输出
npm start
```

### Q: 如何测试服务检查功能？

A: 确保你的 governance-suite 服务正在运行，然后：

```typescript
// 修改 config.example.json 中的端口和地址
// 然后在 Claude 中调用工具
```

### Q: 如何添加新的检查器？

A: 
1. 在 `src/checkers/` 创建新文件
2. 实现检查函数
3. 在 `checkers/index.ts` 中导出
4. 在 `index.ts` 中注册新工具

### Q: TypeScript 编译错误怎么办？

A: 
1. 仔细阅读错误信息
2. 检查类型注解是否正确
3. 使用 IDE 的类型提示
4. 参考 [TYPESCRIPT_GUIDE.md](TYPESCRIPT_GUIDE.md)

## 📚 相关资源

### 文档

- [README.md](README.md) - 项目完整文档
- [TYPESCRIPT_GUIDE.md](TYPESCRIPT_GUIDE.md) - TypeScript 详细教程
- [config.example.json](config.example.json) - 配置示例

### 在线资源

- [TypeScript 官方文档](https://www.typescriptlang.org/)
- [MCP 协议文档](https://modelcontextprotocol.io/)
- [axios 文档](https://axios-http.com/)

### 推荐工具

- **VS Code** - 最佳 TypeScript IDE
- **TypeScript Playground** - 在线测试 TypeScript
- **Postman** - API 测试工具

## 🎯 实践项目建议

### 初级项目

1. **添加更多服务类型**
   - 在 `ServiceType` 枚举中添加新类型
   - 更新预设配置

2. **优化错误信息**
   - 为不同错误类型提供更友好的提示
   - 添加错误分类

3. **实现配置文件加载**
   - 从 JSON 文件读取配置
   - 支持环境变量

### 中级项目

1. **实现 Redis 检查器**
   - 安装 redis 包
   - 完善连接测试
   - 添加性能指标

2. **添加重试机制**
   - 实现指数退避
   - 配置重试次数
   - 记录重试日志

3. **实现并发控制**
   - 限制并发检查数量
   - 实现优先级队列
   - 添加超时控制

### 高级项目

1. **服务依赖图分析**
   - 检测服务间依赖
   - 可视化依赖关系
   - 分析影响范围

2. **智能告警系统**
   - 定义告警规则
   - 实现告警聚合
   - 集成通知渠道

3. **历史趋势分析**
   - 存储检查历史
   - 统计可用性指标
   - 生成趋势报告

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

### 贡献步骤

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

MIT License - 详见 LICENSE 文件

---

**祝你学习愉快！如有问题，欢迎提 Issue。** 🚀
