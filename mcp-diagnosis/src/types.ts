/**
 * 服务健康诊断相关的 TypeScript 类型定义
 *
 * 这个文件展示了 TypeScript 的核心特性：
 * 1. interface - 定义对象结构
 * 2. type - 类型别名
 * 3. enum - 枚举类型
 * 4. 泛型 - 使类型更加灵活
 */

// ============ 枚举类型 ============
// enum 用于定义一组命名的常量
export enum HealthStatus {
  HEALTHY = 'healthy',      // 健康
  UNHEALTHY = 'unhealthy',  // 不健康
  DEGRADED = 'degraded',    // 降级
  UNKNOWN = 'unknown'       // 未知
}

export enum ServiceType {
  GATEWAY = 'gateway',           // 网关服务
  AUTH = 'auth',                 // 认证服务
  USER = 'user',                 // 用户服务
  ORDER = 'order',               // 订单服务
  PAYMENT = 'payment',           // 支付服务
  APPOINTMENT = 'appointment',   // 预约服务
  CHAT = 'chat'                  // 聊天服务
}

// ============ 接口定义 ============
// interface 用于定义对象的形状（shape）

/**
 * 服务信息接口
 * ? 表示可选属性
 */
export interface ServiceInfo {
  name: string;              // 服务名称
  type: ServiceType;         // 服务类型
  host: string;              // 主机地址
  port: number;              // 端口号
  healthEndpoint?: string;   // 健康检查端点（可选）
  managementPort?: number;   // 管理端口（可选）
}

/**
 * 健康检查结果接口
 */
export interface HealthCheckResult {
  service: string;           // 服务名称
  status: HealthStatus;      // 健康状态
  timestamp: Date;           // 检查时间
  responseTime?: number;     // 响应时间（毫秒）
  details?: Record<string, any>;  // 详细信息（Record<K, V> 表示键值对对象）
  error?: string;            // 错误信息
}

/**
 * 数据库连接信息接口
 */
export interface DatabaseConfig {
  type: 'mysql' | 'redis';   // 联合类型：只能是这两个值之一
  host: string;
  port: number;
  database?: string;         // MySQL 数据库名
  username?: string;         // 用户名
  password?: string;         // 密码
}

/**
 * 数据库健康检查结果
 */
export interface DatabaseHealthResult {
  type: string;
  status: HealthStatus;
  connectionTime?: number;   // 连接时间
  error?: string;
}

/**
 * 服务注册中心配置
 */
export interface RegistryConfig {
  type: 'nacos' | 'eureka';  // 注册中心类型
  serverAddr: string;        // 服务器地址
  namespace?: string;        // 命名空间
  group?: string;            // 分组
}

/**
 * 完整的诊断报告
 */
export interface DiagnosisReport {
  timestamp: Date;
  services: HealthCheckResult[];
  databases?: DatabaseHealthResult[];
  registry?: {
    status: HealthStatus;
    registeredServices: number;
    error?: string;
  };
  summary: {
    total: number;           // 总服务数
    healthy: number;         // 健康服务数
    unhealthy: number;       // 不健康服务数
    degraded: number;        // 降级服务数
  };
}

// ============ 类型别名 ============
// type 用于创建类型别名，可以是简单类型、联合类型、交叉类型等

/**
 * 检查器函数类型
 * 泛型 <T> 使这个类型可以适配不同的返回值
 */
export type CheckerFunction<T> = () => Promise<T>;

/**
 * 配置对象类型
 * 使用索引签名 [key: string] 允许任意字符串键
 */
export type ConfigMap = Record<string, any>;

/**
 * 错误处理器类型
 */
export type ErrorHandler = (error: Error) => void;
