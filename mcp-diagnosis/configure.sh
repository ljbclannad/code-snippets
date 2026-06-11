#!/bin/bash

# MCP 服务器配置生成脚本

echo "🔧 生成 Claude Desktop MCP 配置..."

# 获取当前目录的绝对路径
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
BUILD_DIR="${SCRIPT_DIR}/build"
INDEX_PATH="${BUILD_DIR}/index.js"

echo ""
echo "请将以下配置添加到 Claude Desktop 的配置文件中："
echo ""
echo "📍 配置文件位置："
echo "  macOS: ~/Library/Application Support/Claude/claude_desktop_config.json"
echo "  Windows: %APPDATA%\\Claude\\claude_desktop_config.json"
echo ""
echo "📋 配置内容："
echo ""
cat << EOF
{
  "mcpServers": {
    "governance-diagnosis": {
      "command": "node",
      "args": ["${INDEX_PATH}"]
    }
  }
}
EOF

echo ""
echo "✅ 配置完成后，重启 Claude Desktop 即可使用"
echo ""
echo "🎯 使用示例："
echo "  - '使用 quick_diagnose 工具检查本地环境的服务状态'"
echo "  - '诊断以下服务的健康状态：Gateway(localhost:8080)'"
echo ""
