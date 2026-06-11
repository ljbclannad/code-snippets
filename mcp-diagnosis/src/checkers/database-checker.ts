/**
 * 数据库连接检查器
 *
 * 这个模块展示了：
 * 1. 使用第三方库（mysql2）的类型
 * 2. 条件类型和类型判断
 * 3. Promise 错误处理
 */

import mysql from 'mysql2/promise';
import { HealthStatus } from '../types.js';
import type { DatabaseConfig, DatabaseHealthResult } from '../types.js';

/**
 * 检查 MySQL 数据库连接
 *
 * @param config - 数据库配置
 * @returns Promise<DatabaseHealthResult> - 数据库健康检查结果
 *
 * TypeScript 学习点：
 * - 解构赋值的类型推断
 * - 可选属性的处理
 */
export async function checkMySQLConnection(
  config: DatabaseConfig
): Promise<DatabaseHealthResult> {
  const startTime = Date.now();

  try {
    // 创建连接
    // mysql.createConnection 返回 Promise<Connection>
    const connection = await mysql.createConnection({
      host: config.host,
      port: config.port,
      user: config.username,
      password: config.password,
      database: config.database,
      connectTimeout: 5000  // 5秒超时
    });

    // 执行简单查询测试连接
    await connection.execute('SELECT 1');

    // 关闭连接
    await connection.end();

    const connectionTime = Date.now() - startTime;

    return {
      type: 'MySQL',
      status: HealthStatus.HEALTHY,
      connectionTime: connectionTime
    };

  } catch (error) {
    const connectionTime = Date.now() - startTime;
    let errorMessage = 'Unknown database error';

    if (error instanceof Error) {
      errorMessage = error.message;
    }

    return {
      type: 'MySQL',
      status: HealthStatus.UNHEALTHY,
      connectionTime: connectionTime,
      error: errorMessage
    };
  }
}

/**
 * 检查 Redis 连接（简化版本，使用 HTTP 检查）
 *
 * 注意：这是一个简化实现，实际项目中应该使用 redis 客户端
 */
export async function checkRedisConnection(
  config: DatabaseConfig
): Promise<DatabaseHealthResult> {
  // 这里简化处理，实际应该使用 redis 客户端
  // 由于没有安装 redis 包，我们返回一个占位结果
  return {
    type: 'Redis',
    status: HealthStatus.UNKNOWN,
    error: 'Redis checker not fully implemented - needs redis package'
  };
}

/**
 * 统一的数据库检查入口
 *
 * @param config - 数据库配置
 * @returns Promise<DatabaseHealthResult>
 *
 * TypeScript 学习点：
 * - 条件分支中的类型守卫
 * - switch 语句的类型安全
 */
export async function checkDatabaseHealth(
  config: DatabaseConfig
): Promise<DatabaseHealthResult> {
  // 根据数据库类型调用不同的检查函数
  // TypeScript 会确保 config.type 只能是 'mysql' 或 'redis'
  switch (config.type) {
    case 'mysql':
      return checkMySQLConnection(config);
    case 'redis':
      return checkRedisConnection(config);
    default:
      // never 类型表示不应该到达这里
      // 如果到达这里，说明类型定义有问题
      const _exhaustiveCheck: never = config.type;
      throw new Error(`Unsupported database type: ${_exhaustiveCheck}`);
  }
}

/**
 * 批量检查多个数据库
 */
export async function checkMultipleDatabases(
  configs: DatabaseConfig[]
): Promise<DatabaseHealthResult[]> {
  const checks = configs.map(config => checkDatabaseHealth(config));
  return Promise.all(checks);
}
