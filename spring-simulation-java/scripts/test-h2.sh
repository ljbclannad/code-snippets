#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
case "${1:-}" in
    '') main_class=com.spring.simulation.H2TransactionRegressionTests ;;
    --rules) main_class=com.spring.simulation.RollbackRuleRegressionTests ;;
    --rules-demo) main_class=com.spring.simulation.examples.transaction.H2RollbackRuleExperiment ;;
    --demo) main_class=com.spring.simulation.examples.transaction.H2OrderExperiment ;;
    *) echo '用法：sh scripts/test-h2.sh [--demo|--rules|--rules-demo]' >&2; exit 2 ;;
esac
build_dir=$(mktemp -d "${TMPDIR:-/tmp}/spring-h2-tests.XXXXXX")
# 保留临时构建目录，便于检查；不写入项目 bin，也不提交第三方 JAR。
echo "H2 实验构建目录：$build_dir"
h2_jar=${H2_JAR:-"$build_dir/h2-2.4.240.jar"}
if [ -z "${H2_JAR:-}" ]; then
    curl --fail --location --silent --show-error --connect-timeout 15 --max-time 120 \
        https://repo.maven.apache.org/maven2/com/h2database/h2/2.4.240/h2-2.4.240.jar -o "$h2_jar"
fi
expected=29b70e427cc1c40cdc376283adbb0cc62853073797bb5fe5761f81fe73d57ce0
if command -v sha256sum >/dev/null 2>&1; then
    actual=$(sha256sum "$h2_jar" | cut -d ' ' -f 1)
else
    actual=$(shasum -a 256 "$h2_jar" | cut -d ' ' -f 1)
fi
if [ "$actual" != "$expected" ]; then
    echo 'H2 JAR 校验失败：请使用官方 h2-2.4.240.jar。' >&2
    exit 1
fi
find src/main/java src/test/java -name '*.java' > "$build_dir/sources"
javac -encoding UTF-8 -d "$build_dir/classes" @"$build_dir/sources"
java -cp "$build_dir/classes:src/main/resources:$h2_jar" "$main_class"

if [ -z "${1:-}" ]; then
    java -cp "$build_dir/classes:src/main/resources:$h2_jar" com.spring.simulation.RollbackRuleRegressionTests
fi
