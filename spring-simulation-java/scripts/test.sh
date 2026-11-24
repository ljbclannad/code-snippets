#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
case "${1:-}" in
    ''|--h2) ;;
    *) echo '用法：sh scripts/test.sh [--h2]' >&2; exit 2 ;;
esac
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/spring-simulation-tests.XXXXXX")
trap 'rm -rf "$build_dir"' EXIT HUP INT TERM
find src/main/java src/test/java -name '*.java' > "$build_dir/sources"
javac -encoding UTF-8 -d "$build_dir/classes" @"$build_dir/sources"
java -cp "$build_dir/classes:src/main/resources" com.spring.simulation.RegressionTests
java -cp "$build_dir/classes:src/main/resources" com.spring.simulation.TransactionRegressionTests
for demo in context.SpringRefreshDemo context.SpringAdvancedDemo context.SpringEnhancedDemo context.ScopedProxyDemo aop.AopExecutionOrderDemo aop.AopOrderedDemo; do
    java -cp "$build_dir/classes:src/main/resources" "com.spring.simulation.examples.$demo"
done

# 显式启用真实数据库实验，默认验证仍可完全离线运行。
if [ "${1:-}" = "--h2" ]; then
    sh scripts/test-h2.sh
fi
