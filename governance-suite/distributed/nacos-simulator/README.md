# nacos-simulator

一个独立的 Spring Boot 模拟中心，用于在 `governance-suite` 里演示两类 Nacos 核心机制：

- 配置监听侧的长轮询
- 服务注册侧的心跳续约与超时摘除

这个模块不会接管现有业务服务，也不会替换真实 Nacos。现有 `gateway`、`user-service`、`order-service` 等模块仍然继续走原先的 Nacos。

## 目标

这个模块只解决两件事：

1. 演示“客户端带着已知 MD5 发起监听，服务端在无变化时挂起请求，在配置发布后立即唤醒”
2. 演示“实例注册后周期心跳保活，超过 TTL 后被自动摘除”

不做的事情：

- 不兼容 Nacos 全量 OpenAPI
- 不做持久化
- 不做集群一致性
- 不做鉴权
- 不改造现有业务服务接入这里

## 启动

从仓库根目录执行：

```bash
mvn -f distributed/nacos-simulator/pom.xml spring-boot:run
```

默认端口：

```text
http://localhost:8095
```

关键配置在 [application.yml](src/main/resources/application.yml)：

- `simulator.config.long-poll-timeout-ms`
- `simulator.registry.default-ttl-ms`
- `simulator.registry.expiration-scan-interval-ms`

## 核心接口

### 配置接口

- `POST /api/config/publish`
  - 发布或更新配置
- `GET /api/config/get`
  - 查询单个配置
- `POST /api/config/listener`
  - 发起配置监听

`publish` 请求体示例：

```json
{
  "dataId": "common.yml",
  "group": "DEFAULT_GROUP",
  "content": "gateway.rate=5"
}
```

`listener` 请求体示例：

```json
{
  "clientId": "client-a",
  "probes": [
    {
      "dataId": "common.yml",
      "group": "DEFAULT_GROUP",
      "md5": "6b4902c6f5c095e36d9d1d04e5453140"
    }
  ]
}
```

返回语义：

- 若客户端持有的 `md5` 已过期，立即返回 `changed=true`
- 若客户端持有的 `md5` 与服务端一致，请求挂起直到：
  - 配置被修改
  - 达到超时时间

### 注册中心接口

- `POST /api/registry/register`
  - 注册实例
- `POST /api/registry/heartbeat`
  - 刷新心跳
- `POST /api/registry/deregister`
  - 主动下线
- `GET /api/registry/instances`
  - 查询健康实例

`register` 请求体示例：

```json
{
  "serviceName": "order-service",
  "ip": "127.0.0.1",
  "port": 8081,
  "metadata": {
    "zone": "A"
  },
  "ttlMillis": 15000
}
```

实例 ID 规则：

```text
serviceName#ip#port
```

### 管理增强接口

- `GET /api/admin/state`
  - 汇总视图：服务数、健康实例数、挂起监听数
- `GET /api/admin/configs`
  - 查看当前全部配置项
- `GET /api/admin/instances`
  - 查看当前全部实例，可附带 `serviceName` 过滤
- `GET /api/admin/long-polls`
  - 查看当前挂起监听请求

## 典型演示流程

## 1. 演示配置长轮询

### 第一步：发布初始配置

```bash
curl -s -X POST http://localhost:8095/api/config/publish \
  -H 'Content-Type: application/json' \
  -d '{"dataId":"common.yml","group":"DEFAULT_GROUP","content":"gateway.rate=5"}'
```

### 第二步：查询配置拿到当前 MD5

```bash
curl -s "http://localhost:8095/api/config/get?dataId=common.yml&group=DEFAULT_GROUP"
```

响应里会有类似字段：

```json
{
  "dataId": "common.yml",
  "group": "DEFAULT_GROUP",
  "content": "gateway.rate=5",
  "version": 1,
  "md5": "6b4902c6f5c095e36d9d1d04e5453140"
}
```

### 第三步：带着当前 MD5 发起监听

这个请求会挂起：

```bash
curl -s -X POST http://localhost:8095/api/config/listener \
  -H 'Content-Type: application/json' \
  -d '{"clientId":"client-a","probes":[{"dataId":"common.yml","group":"DEFAULT_GROUP","md5":"6b4902c6f5c095e36d9d1d04e5453140"}]}'
```

### 第四步：在另一个终端发布新配置

```bash
curl -s -X POST http://localhost:8095/api/config/publish \
  -H 'Content-Type: application/json' \
  -d '{"dataId":"common.yml","group":"DEFAULT_GROUP","content":"gateway.rate=6"}'
```

此时第三步的监听请求会立即返回，`changed=true`，并携带新内容。

### 第五步：观察挂起监听

在监听请求挂起期间，可以查询：

```bash
curl -s http://localhost:8095/api/admin/long-polls
```

## 2. 演示服务注册与心跳

### 第一步：注册实例

```bash
curl -s -X POST http://localhost:8095/api/registry/register \
  -H 'Content-Type: application/json' \
  -d '{"serviceName":"order-service","ip":"127.0.0.1","port":8081,"metadata":{"zone":"A"},"ttlMillis":5000}'
```

### 第二步：查看实例

```bash
curl -s "http://localhost:8095/api/registry/instances?serviceName=order-service"
```

或查看全部：

```bash
curl -s http://localhost:8095/api/admin/instances
```

### 第三步：发送心跳

```bash
curl -s -X POST http://localhost:8095/api/registry/heartbeat \
  -H 'Content-Type: application/json' \
  -d '{"serviceName":"order-service","instanceId":"order-service#127.0.0.1#8081"}'
```

### 第四步：停止心跳并等待 TTL 超时

如果不再发送心跳，后台任务会按 `expiration-scan-interval-ms` 扫描并摘除过期实例。

之后再次查询：

```bash
curl -s "http://localhost:8095/api/registry/instances?serviceName=order-service"
```

结果会变为空列表。

## 测试

模块测试命令：

```bash
mvn -f distributed/nacos-simulator/pom.xml test
```

如果只跑这个模块的控制器测试：

```bash
mvn -f distributed/nacos-simulator/pom.xml -Dtest=ConfigControllerTest,RegistryControllerTest,AdminControllerTest test
```

说明：

- 当前仓库根 `pom.xml` 里还引用了一个不存在的 `ai/stream-markdown-demo`
- 因此建议继续使用 `-f distributed/nacos-simulator/pom.xml` 做模块级构建与验证

## 代码结构

- [controller](src/main/java/com/lx/governance/nacossimulator/controller/)
  - HTTP 接口
- [service](src/main/java/com/lx/governance/nacossimulator/service/)
  - 配置、长轮询、注册、管理视图
- [store](src/main/java/com/lx/governance/nacossimulator/store/)
  - 内存态并发存储
- [scheduler](src/main/java/com/lx/governance/nacossimulator/scheduler/)
  - 实例过期扫描

## 当前实现边界

当前版本已经支持：

- 配置版本递增
- 配置 MD5 比较
- 配置长轮询挂起与唤醒
- 服务注册
- 心跳续约
- 超时摘除
- 管理侧查看当前配置、实例和挂起监听

当前版本还没有：

- 事件审计列表
- 持久化
- 命名空间
- 鉴权
- 客户端 SDK
