#!/bin/sh
set -e
# ============================================
# DataNest 应用服务统一启动脚本（Sprint 12）
#
# 背景：depends_on 只在 `docker compose up` 时生效；
# Docker daemon 重启（Docker Desktop 关闭再打开/开机自启）时，
# 所有 restart=unless-stopped 容器一窝蜂并发启动、无依赖编排，
# JVM 在中间件就绪前启动会崩溃重启循环。
# 本脚本在 JVM 启动前自等待关键中间件 TCP 可达（轻量 nc 探测，
# 与 compose 健康检查同风格），保证任意启动顺序下服务有序收敛。
#
# 等待项（按环境变量自动跳过未配置项）：
#   NACOS_HOST:NACOS_PORT（8848，HTTP）+ 9848（gRPC）
#   PG_HOST:PG_PORT（持库服务）
#   REDIS_HOST:REDIS_PORT
#   POWERJOB_HOST:POWERJOB_PORT（worker/job，7700）
# JAVA_BIN 可覆盖 java 路径（worker 双 JRE 用绝对路径）
# ============================================

wait_tcp() {
  host="$1"; port="$2"; name="$3"
  if [ -z "$host" ] || [ -z "$port" ]; then
    return 0
  fi
  echo "[wait] $name ($host:$port) ..."
  until nc -z "$host" "$port" 2>/dev/null; do
    sleep 1
  done
  echo "[wait] $name 已就绪"
}

# Nacos：8848（HTTP）+ 9848（gRPC 注册/配置通道）
wait_tcp "${NACOS_HOST}" "${NACOS_PORT}" "Nacos HTTP"
wait_tcp "${NACOS_HOST}" "9848" "Nacos gRPC"
# PostgreSQL（持库服务才注入 PG_HOST）
wait_tcp "${PG_HOST}" "${PG_PORT}" "PostgreSQL"
# Redis（会话/缓存/锁）
wait_tcp "${REDIS_HOST}" "${REDIS_PORT}" "Redis"
# PowerJob（worker/job 执行器才注入）
wait_tcp "${POWERJOB_HOST}" "${POWERJOB_PORT}" "PowerJob"

echo "[wait] 中间件全部就绪，启动服务 ..."
exec "${JAVA_BIN:-java}" org.springframework.boot.loader.launch.JarLauncher "$@"
