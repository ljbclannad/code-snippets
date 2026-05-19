#!/bin/bash

# Doctor Assistant Agent 启动脚本

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 打印带颜色的消息
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 Docker 是否安装
check_docker() {
    if ! command -v docker &> /dev/null; then
        print_error "Docker 未安装，请先安装 Docker"
        exit 1
    fi

    if ! command -v docker-compose &> /dev/null; then
        print_error "Docker Compose 未安装，请先安装 Docker Compose"
        exit 1
    fi

    print_info "Docker 和 Docker Compose 已安装"
}

# 检查环境变量
check_env() {
    if [ -z "$DEEPSEEK_API_KEY" ]; then
        print_warn "DEEPSEEK_API_KEY 环境变量未设置"
        print_warn "将使用 Mock 模式运行（不调用真实 API）"
        export AGENT_USE_MOCK=true
    else
        print_info "DeepSeek API Key 已配置"
        export AGENT_USE_MOCK=false
    fi
}

# 启动基础服务（MySQL + Redis）
start_basic() {
    print_info "启动基础服务（MySQL + Redis）..."
    docker-compose up -d mysql redis

    print_info "等待服务就绪..."
    sleep 10

    print_info "基础服务已启动"
    print_info "MySQL: localhost:3306 (root/root)"
    print_info "Redis: localhost:6379"
}

# 启动完整服务（包括 Milvus）
start_with_milvus() {
    print_info "启动完整服务（MySQL + Redis + Milvus）..."
    docker-compose --profile with-milvus up -d

    print_info "等待服务就绪..."
    sleep 30

    print_info "完整服务已启动"
    print_info "MySQL: localhost:3306 (root/root)"
    print_info "Redis: localhost:6379"
    print_info "Milvus: localhost:19530"
    print_info "Milvus Admin: http://localhost:9091"
}

# 启动监控服务
start_with_monitoring() {
    print_info "启动监控服务（Prometheus + Grafana）..."
    docker-compose --profile with-monitoring up -d

    print_info "等待服务就绪..."
    sleep 10

    print_info "监控服务已启动"
    print_info "Prometheus: http://localhost:9090"
    print_info "Grafana: http://localhost:3000 (admin/admin)"
}

# 启动应用
start_app() {
    print_info "启动 Doctor Assistant Agent 应用..."
    docker-compose up -d doctor-assistant-agent

    print_info "等待应用就绪..."
    sleep 20

    # 检查健康状态
    if curl -f http://localhost:8088/actuator/health &> /dev/null; then
        print_info "应用启动成功！"
        print_info "应用地址: http://localhost:8088"
        print_info "健康检查: http://localhost:8088/actuator/health"
        print_info "Prometheus 指标: http://localhost:8088/actuator/prometheus"
    else
        print_error "应用启动失败，请查看日志: docker-compose logs doctor-assistant-agent"
        exit 1
    fi
}

# 停止所有服务
stop_all() {
    print_info "停止所有服务..."
    docker-compose --profile with-milvus --profile with-monitoring down
    print_info "所有服务已停止"
}

# 查看日志
show_logs() {
    docker-compose logs -f doctor-assistant-agent
}

# 显示帮助信息
show_help() {
    echo "Doctor Assistant Agent 启动脚本"
    echo ""
    echo "用法: $0 [命令]"
    echo ""
    echo "命令:"
    echo "  start           启动基础服务（MySQL + Redis + 应用）"
    echo "  start-full      启动完整服务（包括 Milvus）"
    echo "  start-monitor   启动监控服务（Prometheus + Grafana）"
    echo "  stop            停止所有服务"
    echo "  restart         重启应用"
    echo "  logs            查看应用日志"
    echo "  status          查看服务状态"
    echo "  help            显示帮助信息"
    echo ""
    echo "环境变量:"
    echo "  DEEPSEEK_API_KEY    DeepSeek API Key（可选，未设置时使用 Mock 模式）"
    echo "  MILVUS_ENABLED      是否启用 Milvus（默认: false）"
    echo ""
}

# 查看服务状态
show_status() {
    print_info "服务状态:"
    docker-compose ps
}

# 重启应用
restart_app() {
    print_info "重启应用..."
    docker-compose restart doctor-assistant-agent
    sleep 10
    print_info "应用已重启"
}

# 主函数
main() {
    check_docker
    check_env

    case "${1:-start}" in
        start)
            start_basic
            start_app
            ;;
        start-full)
            start_with_milvus
            start_app
            ;;
        start-monitor)
            start_with_monitoring
            ;;
        stop)
            stop_all
            ;;
        restart)
            restart_app
            ;;
        logs)
            show_logs
            ;;
        status)
            show_status
            ;;
        help)
            show_help
            ;;
        *)
            print_error "未知命令: $1"
            show_help
            exit 1
            ;;
    esac
}

main "$@"
