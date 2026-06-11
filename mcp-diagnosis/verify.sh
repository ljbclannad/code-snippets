#!/bin/bash

# 项目验收脚本
# 自动检查项目完整性

echo "🔍 开始验收项目..."
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 检查计数
PASSED=0
FAILED=0

# 检查函数
check() {
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓${NC} $1"
        ((PASSED++))
    else
        echo -e "${RED}✗${NC} $1"
        ((FAILED++))
    fi
}

echo "📦 1. 检查项目文件结构"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 检查源文件
[ -f "src/types.ts" ]; check "types.ts 存在"
[ -f "src/index.ts" ]; check "index.ts 存在"
[ -f "src/diagnosis-service.ts" ]; check "diagnosis-service.ts 存在"
[ -f "src/checkers/service-checker.ts" ]; check "service-checker.ts 存在"
[ -f "src/checkers/database-checker.ts" ]; check "database-checker.ts 存在"
[ -f "src/checkers/index.ts" ]; check "checkers/index.ts 存在"

echo ""
echo "📚 2. 检查文档文件"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

[ -f "README.md" ]; check "README.md 存在"
[ -f "TYPESCRIPT_GUIDE.md" ]; check "TYPESCRIPT_GUIDE.md 存在"
[ -f "QUICKSTART.md" ]; check "QUICKSTART.md 存在"
[ -f "PROJECT_SUMMARY.md" ]; check "PROJECT_SUMMARY.md 存在"

echo ""
echo "⚙️  3. 检查配置文件"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

[ -f "package.json" ]; check "package.json 存在"
[ -f "tsconfig.json" ]; check "tsconfig.json 存在"
[ -f "config.example.json" ]; check "config.example.json 存在"
[ -f "configure.sh" ]; check "configure.sh 存在"

echo ""
echo "🏗️  4. 检查编译输出"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

[ -d "build" ]; check "build 目录存在"
[ -f "build/index.js" ]; check "index.js 已编译"
[ -f "build/types.js" ]; check "types.js 已编译"
[ -f "build/diagnosis-service.js" ]; check "diagnosis-service.js 已编译"
[ -d "build/checkers" ]; check "checkers 目录已编译"

echo ""
echo "📊 5. 代码质量检查"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 检查 TypeScript 源码行数
LINES=$(find src -name "*.ts" -exec wc -l {} \; | awk '{sum+=$1} END {print sum}')
[ $LINES -gt 800 ]; check "代码量充足 ($LINES 行)"

# 检查文档行数
DOC_LINES=$(find . -maxdepth 1 -name "*.md" -exec wc -l {} \; | awk '{sum+=$1} END {print sum}')
[ $DOC_LINES -gt 600 ]; check "文档完整 ($DOC_LINES 行)"

echo ""
echo "🔧 6. TypeScript 编译测试"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 尝试编译
npm run build > /dev/null 2>&1
check "TypeScript 编译成功"

echo ""
echo "📋 7. 检查 TypeScript 学习覆盖"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# 检查是否包含关键 TypeScript 概念
grep -q "interface" src/types.ts; check "包含接口定义"
grep -q "enum" src/types.ts; check "包含枚举类型"
grep -q "class" src/diagnosis-service.ts; check "包含类定义"
grep -q "async" src/checkers/service-checker.ts; check "包含异步编程"
grep -q "private" src/diagnosis-service.ts; check "包含访问修饰符"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "📈 验收结果统计"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

TOTAL=$((PASSED + FAILED))
PERCENTAGE=$((PASSED * 100 / TOTAL))

echo -e "总检查项: $TOTAL"
echo -e "${GREEN}通过: $PASSED${NC}"
echo -e "${RED}失败: $FAILED${NC}"
echo -e "通过率: ${PERCENTAGE}%"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}🎉 恭喜！项目验收通过！${NC}"
    echo ""
    echo "✨ 你已经成功创建了一个完整的 MCP 诊断工具！"
    echo ""
    echo "📖 下一步："
    echo "   1. 运行 ./configure.sh 配置 Claude Desktop"
    echo "   2. 阅读 TYPESCRIPT_GUIDE.md 学习 TypeScript"
    echo "   3. 在 Claude 中测试工具功能"
    echo ""
else
    echo -e "${YELLOW}⚠️  项目有 $FAILED 项未通过验收${NC}"
    echo ""
    echo "请检查上述失败项并修复"
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
