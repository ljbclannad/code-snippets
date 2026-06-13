# 项目模块重组实施计划

## 目标

将 governance-suite 项目按功能领域重新组织：
- **AI 服务** → `ai/` 目录
- **分布式系统服务** → `distributed/` 目录  
- **公共模块** → 保持在根目录（`common/`）

## 新目录结构

```
governance-suite/
├── common/                      # 保持不变
├── ai/                          # 新建：AI 相关服务
│   ├── ai-chat-service/
│   ├── user-profile-service/
│   ├── vector-memory-service/
│   ├── doctor-assistant-agent/
│   └── knowledge-base-service/
├── distributed/                 # 新建：分布式治理服务
│   ├── gateway/
│   ├── user-service/
│   ├── order-service/
│   ├── payment-service/
│   ├── inventory-service/
│   ├── outbox-consumer/
│   └── appointment-service/
├── docs/
├── sh/
├── pom.xml
└── docker-compose.yml
```

## 实施步骤

### 阶段 1: 创建目录结构并移动模块

**1.1 创建顶级目录**
```bash
mkdir -p ai distributed
```

**1.2 移动 AI 服务（5个模块）**
```bash
git mv ai-chat-service ai/
git mv user-profile-service ai/
git mv vector-memory-service ai/
git mv doctor-assistant-agent ai/
git mv knowledge-base-service ai/
```

**1.3 移动分布式服务（7个模块）**
```bash
git mv gateway distributed/
git mv user-service distributed/
git mv order-service distributed/
git mv payment-service distributed/
git mv inventory-service distributed/
git mv outbox-consumer distributed/
git mv appointment-service distributed/
```

### 阶段 2: 更新 Maven 配置

**2.1 更新根 pom.xml 的 modules 声明**

将原来的：
```xml
<modules>
  <module>common</module>
  <module>gateway</module>
  <module>user-service</module>
  ...
</modules>
```

改为：
```xml
<modules>
  <module>common</module>
  
  <!-- Distributed Services -->
  <module>distributed/gateway</module>
  <module>distributed/user-service</module>
  <module>distributed/order-service</module>
  <module>distributed/payment-service</module>
  <module>distributed/inventory-service</module>
  <module>distributed/outbox-consumer</module>
  <module>distributed/appointment-service</module>
  
  <!-- AI Services -->
  <module>ai/ai-chat-service</module>
  <module>ai/user-profile-service</module>
  <module>ai/vector-memory-service</module>
  <module>ai/doctor-assistant-agent</module>
  <module>ai/knowledge-base-service</module>
</modules>
```

**2.2 更新各服务的 pom.xml parent relativePath**

对于 `ai/` 下的所有服务，在 `<parent>` 标签中添加：
```xml
<parent>
  <groupId>com.lx.governance</groupId>
  <artifactId>governance-suite</artifactId>
  <version>1.0.0</version>
  <relativePath>../../pom.xml</relativePath>
</parent>
```

对于 `distributed/` 下的所有服务，同样添加：
```xml
<parent>
  <groupId>com.lx.governance</groupId>
  <artifactId>governance-suite</artifactId>
  <version>1.0.0</version>
  <relativePath>../../pom.xml</relativePath>
</parent>
```

注意：`common/pom.xml` 保持使用 `../pom.xml`

### 阶段 3: 更新脚本

**3.1 更新 sh/run/start-all-services.sh**

将所有 `mvn -q -pl <service>` 改为 `mvn -q -pl distributed/<service>`：
```bash
# 原来: mvn -q -pl gateway spring-boot:run
# 改为:
mvn -q -pl distributed/gateway spring-boot:run
mvn -q -pl distributed/user-service spring-boot:run
mvn -q -pl distributed/order-service spring-boot:run
mvn -q -pl distributed/payment-service spring-boot:run
mvn -q -pl distributed/inventory-service spring-boot:run
mvn -q -pl distributed/outbox-consumer spring-boot:run
```

**3.2 更新 sh/run/start-ai-services.sh**

第 174 行的启动命令：
```bash
# 原来: nohup mvn -q -pl "${service}" spring-boot:run
# 改为:
nohup mvn -q -pl "ai/${service}" spring-boot:run > "${log_file}" 2>&1 &
```

**3.3 更新 sh/run/stop-ai-services.sh**

检查是否有模块路径引用，如果有则更新。

**3.4 更新 sh/verify/verify-monitoring.sh**

将 Maven 命令中的模块路径更新：
```bash
# 原来: mvn -q -pl ai-chat-service spring-boot:run
# 改为:
mvn -q -pl ai/ai-chat-service spring-boot:run
```

### 阶段 4: 更新文档

**4.1 更新 CLAUDE.md**

- 更新 "Common service runs" 部分的所有命令示例
- 更新 "Failure injection" 部分的命令
- 更新 "Maven" 验证示例

将：
```bash
mvn -f common/pom.xml compile
mvn -f order-service/pom.xml spring-boot:run
```

改为：
```bash
mvn -f common/pom.xml compile
mvn -f distributed/order-service/pom.xml spring-boot:run
mvn -f ai/ai-chat-service/pom.xml spring-boot:run
```

**4.2 更新 README.md**

- 更新快速开始部分的 Maven 命令
- 更新项目结构说明
- 添加新的目录组织说明

**4.3 检查并更新 docs/ 下的文档**

虽然文档中有 903 处服务名引用，但大部分是概念性引用（服务名、端口），不是文件路径。需要重点更新：
- Maven 命令示例
- 文件路径引用
- 目录结构图

### 阶段 5: 验证

**5.1 验证 Maven 构建**
```bash
# 验证根编译
mvn clean compile -DskipTests

# 验证各个模块
mvn -f common/pom.xml compile
mvn -f distributed/gateway/pom.xml compile
mvn -f distributed/order-service/pom.xml compile
mvn -f ai/ai-chat-service/pom.xml compile
mvn -f ai/doctor-assistant-agent/pom.xml compile
```

**5.2 验证模块路径引用**
```bash
# 使用新路径启动服务测试
mvn -q -pl distributed/gateway spring-boot:run
mvn -q -pl ai/ai-chat-service spring-boot:run
```

**5.3 验证脚本**
```bash
# 测试启动脚本
./sh/run/start-ai-services.sh --help
# 实际不启动，只验证语法和路径
bash -n sh/run/start-all-services.sh
bash -n sh/run/start-ai-services.sh
```

**5.4 验证服务运行**
- 启动基础设施：`docker compose up -d`
- 使用新脚本启动一个分布式服务
- 使用新脚本启动一个 AI 服务
- 检查 Nacos 注册情况
- 验证服务能正常响应请求

## 风险评估

### 低风险
- ✅ 服务注册发现：Nacos 基于服务名，不受文件路径影响
- ✅ 服务间通信：使用服务名调用，与物理路径无关
- ✅ 配置管理：application.yml 中的配置不依赖模块路径
- ✅ Docker 基础设施：docker-compose.yml 不引用服务代码路径

### 需要仔细处理
- ⚠️ Maven 模块依赖：common 被多个服务依赖，但这是 GAV 坐标依赖，不是相对路径
- ⚠️ Maven parent 引用：需要更新 relativePath，但这是标准操作
- ⚠️ 脚本中的 Maven 命令：需要系统性更新所有 `-pl` 和 `-f` 参数
- ⚠️ 文档中的命令示例：需要更新，但不影响运行

### 回滚方案
如果验证失败，可以使用：
```bash
git reset --hard HEAD
```
因为所有操作都是在一个 commit 中完成的。

## 预期影响范围

### 需要修改的文件（估计）
- 根 pom.xml：1 个
- 各服务 pom.xml：12 个（7个分布式 + 5个AI）
- 脚本文件：3-4 个（sh/run/*, sh/verify/*）
- 文档文件：2-3 个（CLAUDE.md, README.md, 可能个别 docs/ 下的文档）
- **总计：约 18-20 个文件**

### 不需要修改的内容
- 所有 Java 源代码
- 所有配置文件（application.yml, application-*.yml）
- docker-compose.yml
- 测试脚本（sh/test/）中的业务逻辑测试，因为它们通过网关或直接访问端口

## 实施建议

1. **一次性完成**：这是一个结构性重构，应该在一个 commit 中完成
2. **测试优先**：重构后立即进行编译和启动测试
3. **保持服务名不变**：只改变文件路径，不改变服务的 spring.application.name
4. **文档同步更新**：确保文档与代码结构保持一致

## 成功标准

✅ Maven reactor 构建成功  
✅ 至少一个分布式服务能正常启动并注册到 Nacos  
✅ 至少一个 AI 服务能正常启动并注册到 Nacos  
✅ 脚本语法检查通过  
✅ 文档中的命令示例准确反映新结构
