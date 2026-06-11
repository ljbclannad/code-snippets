/**
 * 服务健康检查器
 *
 * 这个模块展示了 TypeScript 的以下特性：
 * 1. import/export 模块系统
 * 2. async/await 异步编程
 * 3. 类型注解和类型推断
 * 4. 错误处理
 */

import axios, { AxiosError } from 'axios';
import { HealthStatus } from '../types.js';
import type { ServiceInfo, HealthCheckResult } from '../types.js';

/**
 * 检查单个服务的健康状态
 *
 * @param service - 服务信息对象
 * @returns Promise<HealthCheckResult> - 返回健康检查结果
 *
 * TypeScript 学习点：
 * - async 函数返回 Promise 类型
 * - 参数类型注解：service: ServiceInfo
 * - 返回类型注解：Promise<HealthCheckResult>
 */
export async function checkServiceHealth(
  service: ServiceInfo
): Promise<HealthCheckResult> {
  // 记录开始时间（用于计算响应时间）
  const startTime = Date.now();

  // 构建健康检查 URL
  // 模板字符串使用反引号 ` 和 ${}
  const healthEndpoint = service.healthEndpoint || '/actuator/health';
  const url = `http://${service.host}:${service.port}${healthEndpoint}`;

  try {
    // await 等待 Promise 完成
    // axios.get 返回 Promise<AxiosResponse>
    const response = await axios.get(url, {
      timeout: 5000,  // 5秒超时
      validateStatus: (status) => status < 500  // 500 以下都视为成功响应
    });

    // 计算响应时间
    const responseTime = Date.now() - startTime;

    // 根据 HTTP 状态码判断健康状态
    let status: HealthStatus;
    if (response.status === 200) {
      status = HealthStatus.HEALTHY;
    } else if (response.status >= 400) {
      status = HealthStatus.UNHEALTHY;
    } else {
      status = HealthStatus.DEGRADED;
    }

    // 返回结果对象
    // TypeScript 会检查这个对象是否符合 HealthCheckResult 接口
    return {
      service: service.name,
      status: status,
      timestamp: new Date(),
      responseTime: responseTime,
      details: {
        httpStatus: response.status,
        data: response.data
      }
    };

  } catch (error) {
    // 错误处理
    // TypeScript 中 catch 的 error 默认是 unknown 类型
    // 我们需要类型守卫来缩窄类型

    const responseTime = Date.now() - startTime;
    let errorMessage = 'Unknown error';

    // 类型守卫：检查是否是 AxiosError
    if (axios.isAxiosError(error)) {
      const axiosError = error as AxiosError;
      if (axiosError.code === 'ECONNREFUSED') {
        errorMessage = 'Connection refused - service may be down';
      } else if (axiosError.code === 'ETIMEDOUT') {
        errorMessage = 'Connection timeout';
      } else {
        errorMessage = axiosError.message;
      }
    } else if (error instanceof Error) {
      errorMessage = error.message;
    }

    return {
      service: service.name,
      status: HealthStatus.UNHEALTHY,
      timestamp: new Date(),
      responseTime: responseTime,
      error: errorMessage
    };
  }
}

/**
 * 批量检查多个服务
 *
 * @param services - 服务信息数组
 * @returns Promise<HealthCheckResult[]> - 返回所有服务的检查结果
 *
 * TypeScript 学习点：
 * - 数组类型注解：ServiceInfo[]
 * - Promise.all 并行执行多个异步操作
 * - map 函数的类型推断
 */
export async function checkMultipleServices(
  services: ServiceInfo[]
): Promise<HealthCheckResult[]> {
  // 使用 map 将每个服务映射为一个 Promise
  // Promise.all 并行执行所有检查
  const checks = services.map(service => checkServiceHealth(service));

  // await Promise.all 等待所有 Promise 完成
  const results = await Promise.all(checks);

  return results;
}

/**
 * 获取服务的详细指标信息（如果支持 Spring Boot Actuator）
 *
 * @param service - 服务信息
 * @returns Promise<any> - 返回指标数据
 */
export async function getServiceMetrics(service: ServiceInfo): Promise<any> {
  const managementPort = service.managementPort || service.port;
  const url = `http://${service.host}:${managementPort}/actuator/metrics`;

  try {
    const response = await axios.get(url, { timeout: 3000 });
    return response.data;
  } catch (error) {
    // 如果获取指标失败，返回 null
    return null;
  }
}
