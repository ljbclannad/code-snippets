/**
 * 诊断服务核心模块
 *
 * 这个模块展示了：
 * 1. 类（Class）的使用
 * 2. 访问修饰符（private, public）
 * 3. 构造函数和成员方法
 * 4. 异步方法的实现
 */

import type {
  ServiceInfo,
  DatabaseConfig,
  DiagnosisReport,
  HealthCheckResult,
  DatabaseHealthResult
} from './types.js';
import { HealthStatus } from './types.js';
import {
  checkMultipleServices,
  checkMultipleDatabases
} from './checkers/index.js';

/**
 * 诊断配置接口
 */
export interface DiagnosisConfig {
  services: ServiceInfo[];
  databases?: DatabaseConfig[];
  includeMetrics?: boolean;  // 是否包含详细指标
}

/**
 * 诊断服务类
 *
 * TypeScript 学习点：
 * - class 声明
 * - 成员变量的类型声明
 * - 访问修饰符
 */
export class DiagnosisService {
  // private 成员只能在类内部访问
  private config: DiagnosisConfig;

  /**
   * 构造函数
   *
   * @param config - 诊断配置
   *
   * TypeScript 学习点：
   * - constructor 的类型注解
   * - this 关键字的使用
   */
  constructor(config: DiagnosisConfig) {
    this.config = config;
  }

  /**
   * 执行完整的诊断
   *
   * @returns Promise<DiagnosisReport> - 诊断报告
   *
   * TypeScript 学习点：
   * - async 方法返回 Promise
   * - 对象字面量的类型推断
   */
  public async runDiagnosis(): Promise<DiagnosisReport> {
    console.log('🔍 开始运行诊断...');

    // 检查所有服务
    console.log(`📡 检查 ${this.config.services.length} 个服务...`);
    const serviceResults = await checkMultipleServices(this.config.services);

    // 检查数据库（如果有配置）
    let databaseResults: DatabaseHealthResult[] | undefined;
    if (this.config.databases && this.config.databases.length > 0) {
      console.log(`💾 检查 ${this.config.databases.length} 个数据库...`);
      databaseResults = await checkMultipleDatabases(this.config.databases);
    }

    // 生成摘要统计
    const summary = this.generateSummary(serviceResults);

    // 构建完整报告
    const report: DiagnosisReport = {
      timestamp: new Date(),
      services: serviceResults,
      databases: databaseResults,
      summary: summary
    };

    console.log('✅ 诊断完成！');
    return report;
  }

  /**
   * 生成摘要统计
   *
   * @param results - 健康检查结果数组
   * @returns 统计摘要对象
   *
   * TypeScript 学习点：
   * - 数组的 reduce 方法
   * - 类型推断和累加器模式
   */
  private generateSummary(results: HealthCheckResult[]) {
    // 使用 reduce 统计各状态的数量
    const counts = results.reduce(
      (acc, result) => {
        // acc 是累加器，result 是当前元素
        if (result.status === HealthStatus.HEALTHY) {
          acc.healthy++;
        } else if (result.status === HealthStatus.UNHEALTHY) {
          acc.unhealthy++;
        } else if (result.status === HealthStatus.DEGRADED) {
          acc.degraded++;
        }
        return acc;
      },
      // 初始值：包含各计数器的对象
      { healthy: 0, unhealthy: 0, degraded: 0 }
    );

    return {
      total: results.length,
      healthy: counts.healthy,
      unhealthy: counts.unhealthy,
      degraded: counts.degraded
    };
  }

  /**
   * 格式化报告为易读的文本
   *
   * @param report - 诊断报告
   * @returns 格式化的字符串
   */
  public formatReport(report: DiagnosisReport): string {
    const lines: string[] = [];

    lines.push('═══════════════════════════════════════════');
    lines.push('📊 分布式系统健康诊断报告');
    lines.push('═══════════════════════════════════════════');
    lines.push(`⏰ 检查时间: ${report.timestamp.toISOString()}`);
    lines.push('');

    // 摘要
    lines.push('📈 总体摘要:');
    lines.push(`   总计: ${report.summary.total} 个服务`);
    lines.push(`   ✅ 健康: ${report.summary.healthy}`);
    lines.push(`   ⚠️  降级: ${report.summary.degraded}`);
    lines.push(`   ❌ 异常: ${report.summary.unhealthy}`);
    lines.push('');

    // 服务详情
    lines.push('📡 服务状态:');
    for (const service of report.services) {
      const icon = this.getStatusIcon(service.status);
      const timeInfo = service.responseTime
        ? ` (${service.responseTime}ms)`
        : '';
      lines.push(`   ${icon} ${service.service}${timeInfo}`);
      if (service.error) {
        lines.push(`      错误: ${service.error}`);
      }
    }
    lines.push('');

    // 数据库详情
    if (report.databases && report.databases.length > 0) {
      lines.push('💾 数据库状态:');
      for (const db of report.databases) {
        const icon = this.getStatusIcon(db.status);
        const timeInfo = db.connectionTime ? ` (${db.connectionTime}ms)` : '';
        lines.push(`   ${icon} ${db.type}${timeInfo}`);
        if (db.error) {
          lines.push(`      错误: ${db.error}`);
        }
      }
      lines.push('');
    }

    lines.push('═══════════════════════════════════════════');

    return lines.join('\n');
  }

  /**
   * 根据状态获取对应的图标
   */
  private getStatusIcon(status: HealthStatus): string {
    switch (status) {
      case HealthStatus.HEALTHY:
        return '✅';
      case HealthStatus.UNHEALTHY:
        return '❌';
      case HealthStatus.DEGRADED:
        return '⚠️';
      case HealthStatus.UNKNOWN:
        return '❓';
      default:
        return '❓';
    }
  }
}
