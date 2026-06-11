# Governance Suite 分布式系统健康诊断 MCP

这是一个用于诊断 governance-suite 分布式微服务系统健康状态的 Model Context Protocol (MCP) 服务器。

## 🎯 功能特性

- ✅ **服务健康检查**：检查微服务是否在线及响应时间
- 💾 **数据库连接检查**：支持 MySQL 和 Redis 连接测试
- 📊 **统计报告**：生成清晰的健康状态摘要
- 🚀 **快速诊断**：使用预设配置快速检查常见服务
- 🔧 **灵活配置**：支持自定义服务列表和检查参数

## 📁 项目结构

```
mcp-diagnosis/
├── src/
│   ├── index.ts                    # MCP 服务器入口
│   ├── types.ts                    # TypeScript 类型定义
│   ├── diagnosis-service.ts        # 诊断服务核心逻辑
│   └── checkers/                   # 检查器模块
│       ├── index.ts                # 统一导出
│       ├── service-checker.ts      # 服务健康检查
│       └── database-checker.ts     # 数据库连接检查
├── package.json
├── tsconfig.json
└── README.md
```

## 🚀 快速开始

### 1. 安装依赖

```bash
npm install
```

### 2. 编译 TypeScript

```bash
npm run build
```

### 3. 启动 MCP 服务器

```bash
npm start
```

## 🛠️ 可用工具

### 1. `diagnose_services`

完全自定义的服务诊断工具。

**参数：**

```json
{
  "services": [
    {
      "name": "Gateway",
      "type": "gateway",
      "host": "localhost",
      "port": 8080,
      "healthEndpoint": "/actuator/health"
    }
  ],
  "databases": [
    {
      "type": "mysql",
      "host": "localhost",
      "port": 3306,
      "database": "governance_suite",
      "username": "root",
      "password": "password"
    }
  ]
}
```

### 2. `quick_diagnose`

使用预设配置快速诊断。

**参数：**

```json
{
  "environment": "local",  // local | dev | test | prod
  "includeDatabase": false
}
```

## 💡 TypeScript 学习要点

这个项目是学习 TypeScript 的绝佳示例，涵盖了以下关键概念：

### 1. 类型系统
- **基本类型**：string, number, boolean
- **接口（Interface）**：定义对象结构
- **类型别名（Type）**：创建可复用的类型
- **枚举（Enum）**：定义命名常量集合
- **联合类型**：`'mysql' | 'redis'`
- **泛型**：`Promise<T>`, `Array<T>`

### 2. 模块系统
- **import/export**：ES6 模块语法
- **类型导入**：`import type { ... }`
- **重新导出**：`export * from './module'`

### 3. 异步编程
- **Promise**：异步操作的抽象
- **async/await**：同步风格的异步代码
- **Promise.all**：并行执行多个异步操作

### 4. 面向对象
- **类（Class）**：封装数据和行为
- **访问修饰符**：private, public
- **构造函数**：初始化对象状态
- **方法**：类的行为

### 5. 错误处理
- **try-catch**：捕获异常
- **类型守卫**：缩窄错误类型
- **自定义错误处理**：优雅地处理各种错误场景

## 📝 示例使用

### 在 Claude Desktop 中配置

编辑 `claude_desktop_config.json`：

```json
{
  "mcpServers": {
    "governance-diagnosis": {
      "command": "node",
      "args": ["/path/to/mcp-diagnosis/build/index.js"]
    }
  }
}
```

### 调用示例

在 Claude 对话中：

```
请使用 quick_diagnose 工具检查本地环境的服务状态
```

或者：

```
使用 diagnose_services 检查以下服务：
- Gateway: localhost:8080
- Auth Service: localhost:8081
```

## 🔍 检查器详解

### 服务检查器 (service-checker.ts)

- 使用 axios 发送 HTTP 请求到健康检查端点
- 支持自定义健康检查路径（默认 `/actuator/health`）
- 计算响应时间
- 根据 HTTP 状态码判断健康状态

### 数据库检查器 (database-checker.ts)

- **MySQL**：使用 mysql2 创建连接并执行测试查询
- **Redis**：预留接口（需要安装 redis 包）
- 计算连接时间
- 捕获连接错误

## 🎓 学习建议

1. **从类型开始**：先看 [types.ts](src/types.ts)，理解数据结构
2. **理解检查器**：阅读 checkers 目录中的代码，了解如何进行健康检查
3. **学习服务类**：查看 [diagnosis-service.ts](src/diagnosis-service.ts)，理解面向对象设计
4. **研究 MCP 集成**：最后看 [index.ts](src/index.ts)，了解如何创建 MCP 服务器

## 🔧 开发命令

```bash
# 编译
npm run build

# 启动
npm start

# 开发模式（编译+启动）
npm run dev

# 清理构建文件
npm run clean
```

## 📚 相关资源

- [TypeScript 官方文档](https://www.typescriptlang.org/docs/)
- [MCP SDK 文档](https://modelcontextprotocol.io/docs)
- [Axios 文档](https://axios-http.com/)
- [mysql2 文档](https://github.com/sidorares/node-mysql2)

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

MIT License
