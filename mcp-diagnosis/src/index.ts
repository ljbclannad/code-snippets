/**
 * MCP 服务器主入口
 *
 * 这个模块展示了：
 * 1. MCP SDK 的使用
 * 2. 服务器的创建和配置
 * 3. 工具（Tools）的注册
 * 4. 异步流程控制
 */

import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import {
  CallToolRequestSchema,
  ListToolsRequestSchema,
} from '@modelcontextprotocol/sdk/types.js';

import { DiagnosisService, DiagnosisConfig } from './diagnosis-service.js';
import type { ServiceInfo, DatabaseConfig, ServiceType } from './types.js';

/**
 * 创建 MCP 服务器实例
 *
 * TypeScript 学习点：
 * - 实例化类
 * - 泛型的使用
 */
const server = new Server(
  {
    name: 'governance-diagnosis',
    version: '1.0.0',
  },
  {
    capabilities: {
      tools: {},  // 声明支持工具
    },
  }
);

/**
 * 注册工具列表处理器
 *
 * 当 MCP 客户端请求可用工具列表时，会调用这个处理器
 *
 * TypeScript 学习点：
 * - 箭头函数
 * - async 箭头函数
 * - 对象字面量
 */
server.setRequestHandler(ListToolsRequestSchema, async () => {
  return {
    tools: [
      {
        name: 'diagnose_services',
        description: '诊断 governance-suite 分布式系统中的服务健康状态',
        inputSchema: {
          type: 'object',
          properties: {
            services: {
              type: 'array',
              description: '要检查的服务列表',
              items: {
                type: 'object',
                properties: {
                  name: { type: 'string', description: '服务名称' },
                  type: {
                    type: 'string',
                    enum: ['gateway', 'auth', 'user', 'order', 'payment', 'appointment', 'chat'],
                    description: '服务类型'
                  },
                  host: { type: 'string', description: '主机地址' },
                  port: { type: 'number', description: '端口号' },
                  healthEndpoint: { type: 'string', description: '健康检查端点（可选）' }
                },
                required: ['name', 'type', 'host', 'port']
              }
            },
            databases: {
              type: 'array',
              description: '要检查的数据库列表（可选）',
              items: {
                type: 'object',
                properties: {
                  type: {
                    type: 'string',
                    enum: ['mysql', 'redis'],
                    description: '数据库类型'
                  },
                  host: { type: 'string', description: '主机地址' },
                  port: { type: 'number', description: '端口号' },
                  database: { type: 'string', description: '数据库名（MySQL）' },
                  username: { type: 'string', description: '用户名' },
                  password: { type: 'string', description: '密码' }
                },
                required: ['type', 'host', 'port']
              }
            }
          },
          required: ['services']
        }
      },
      {
        name: 'quick_diagnose',
        description: '使用预设配置快速诊断常见的 governance-suite 服务',
        inputSchema: {
          type: 'object',
          properties: {
            environment: {
              type: 'string',
              enum: ['local', 'dev', 'test', 'prod'],
              description: '环境：local(本地)、dev(开发)、test(测试)、prod(生产)',
              default: 'local'
            },
            includeDatabase: {
              type: 'boolean',
              description: '是否包含数据库检查',
              default: false
            }
          }
        }
      }
    ],
  };
});

/**
 * 注册工具调用处理器
 *
 * 当 MCP 客户端调用工具时，会调用这个处理器
 *
 * TypeScript 学习点：
 * - 模式匹配（switch）
 * - 类型断言（as）
 * - try-catch 错误处理
 */
server.setRequestHandler(CallToolRequestSchema, async (request) => {
  const { name, arguments: args } = request.params;

  try {
    if (name === 'diagnose_services') {
      // 检查参数是否存在
      if (!args) {
        throw new Error('Missing arguments');
      }

      // 解析参数
      const services = args.services as ServiceInfo[];
      const databases = args.databases as DatabaseConfig[] | undefined;

      // 创建诊断配置
      const config: DiagnosisConfig = {
        services,
        databases
      };

      // 执行诊断
      const diagnosisService = new DiagnosisService(config);
      const report = await diagnosisService.runDiagnosis();
      const formattedReport = diagnosisService.formatReport(report);

      return {
        content: [
          {
            type: 'text',
            text: formattedReport
          }
        ]
      };

    } else if (name === 'quick_diagnose') {
      // 快速诊断使用预设配置
      const environment = (args?.environment as string) || 'local';
      const includeDatabase = (args?.includeDatabase as boolean) || false;

      const config = getPresetConfig(environment, includeDatabase);
      const diagnosisService = new DiagnosisService(config);
      const report = await diagnosisService.runDiagnosis();
      const formattedReport = diagnosisService.formatReport(report);

      return {
        content: [
          {
            type: 'text',
            text: formattedReport
          }
        ]
      };

    } else {
      throw new Error(`Unknown tool: ${name}`);
    }

  } catch (error) {
    // 错误处理
    const errorMessage = error instanceof Error ? error.message : 'Unknown error';
    return {
      content: [
        {
          type: 'text',
          text: `❌ 诊断失败: ${errorMessage}`
        }
      ],
      isError: true
    };
  }
});

/**
 * 获取预设配置
 *
 * @param environment - 环境名称
 * @param includeDatabase - 是否包含数据库
 * @returns 诊断配置
 *
 * TypeScript 学习点：
 * - 函数参数默认值
 * - 条件表达式
 * - 数组字面量
 */
function getPresetConfig(
  environment: string,
  includeDatabase: boolean
): DiagnosisConfig {
  // 根据环境确定主机地址
  const host = environment === 'local' ? 'localhost' : `${environment}.governance-suite.com`;

  // 预设的服务列表
  const services: ServiceInfo[] = [
    {
      name: 'Gateway',
      type: 'gateway' as ServiceType,
      host: host,
      port: 8080
    },
    {
      name: 'Auth Service',
      type: 'auth' as ServiceType,
      host: host,
      port: 8081
    },
    {
      name: 'User Service',
      type: 'user' as ServiceType,
      host: host,
      port: 8082
    },
    {
      name: 'Order Service',
      type: 'order' as ServiceType,
      host: host,
      port: 8083
    }
  ];

  // 如果需要包含数据库
  const databases: DatabaseConfig[] | undefined = includeDatabase
    ? [
        {
          type: 'mysql',
          host: host,
          port: 3306,
          database: 'governance_suite',
          username: 'root',
          password: 'password'
        }
      ]
    : undefined;

  return {
    services,
    databases
  };
}

/**
 * 启动 MCP 服务器
 *
 * TypeScript 学习点：
 * - async 立即执行函数表达式（IIFE）
 * - top-level await
 * - 错误处理和进程退出
 */
async function main() {
  console.error('🚀 启动 Governance Diagnosis MCP 服务器...');

  // 创建标准输入输出传输层
  const transport = new StdioServerTransport();

  // 连接服务器和传输层
  await server.connect(transport);

  console.error('✅ MCP 服务器已启动并准备接收请求');
}

// 启动服务器
main().catch((error) => {
  console.error('❌ 服务器启动失败:', error);
  process.exit(1);
});
