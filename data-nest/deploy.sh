#!/usr/bin/env bash
# ============================================
# DataNest 一键部署脚本（Sprint 12 F1，ADR-S12-001）
#
# 用法:
#   ./deploy.sh [选项]
#
# 选项:
#   --skip-build            跳过后端/前端构建，仅重新部署
#   --skip-doris            跳过 Doris 配置与连通性校验（同步与数仓功能不可用）
#   --with-test-deps        额外拉起 E2E 测试库（test-mysql/test-postgres/test-oracle/test-sqlserver）
#   --doris-host=HOST       Doris FE 主机（非交互模式）
#   --doris-port=PORT       Doris FE 查询端口，默认 9030
#   --doris-user=USER       Doris 用户，默认 root
#   --doris-password=PASS   Doris 密码
#   -h, --help              显示帮助
#
# 流程（七段式）:
#   [1/7] 环境预检 → [2/7] Doris 配置 → [3/7] 后端构建 → [4/7] 前端构建
#   → [5/7] compose up → [6/7] 健康等待 + 登录冒烟 → [7/7] 打印访问信息
# ============================================
set -euo pipefail

cd "$(dirname "$0")"

# ---------- 参数 ----------
SKIP_BUILD=0
SKIP_DORIS=0
WITH_TEST_DEPS=0
DORIS_HOST="" DORIS_PORT="" DORIS_USER="" DORIS_PASSWORD=""

for arg in "$@"; do
  case "$arg" in
    --skip-build)      SKIP_BUILD=1 ;;
    --skip-doris)      SKIP_DORIS=1 ;;
    --with-test-deps)  WITH_TEST_DEPS=1 ;;
    --doris-host=*)    DORIS_HOST="${arg#*=}" ;;
    --doris-port=*)    DORIS_PORT="${arg#*=}" ;;
    --doris-user=*)    DORIS_USER="${arg#*=}" ;;
    --doris-password=*) DORIS_PASSWORD="${arg#*=}" ;;
    -h|--help)
      sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg（用 -h 查看帮助）"; exit 1 ;;
  esac
done

# ---------- 日志 ----------
if [ -t 1 ]; then
  C_INFO='\033[0;36m'; C_OK='\033[0;32m'; C_WARN='\033[0;33m'; C_ERR='\033[0;31m'; C_OFF='\033[0m'
else
  C_INFO=''; C_OK=''; C_WARN=''; C_ERR=''; C_OFF=''
fi
info() { echo -e "${C_INFO}[INFO]${C_OFF} $*"; }
ok()   { echo -e "${C_OK}[ OK ]${C_OFF} $*"; }
warn() { echo -e "${C_WARN}[WARN]${C_OFF} $*"; }
err()  { echo -e "${C_ERR}[FAIL]${C_OFF} $*" >&2; }

CURRENT_STAGE="启动"
trap 'err "阶段「${CURRENT_STAGE}」失败，已中止。排查见上方输出；常见问题见 docs/deploy.md。" >&2' ERR

IS_TTY=0; [ -t 0 ] && IS_TTY=1

# Windows Git Bash 下裸 mvn 有 classworlds 路径解析坑，改用 mvn.cmd
MVN="mvn"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) MVN="mvn.cmd" ;; esac

DORIS_YAML="shared-configs/shared-doris.yaml"

# ============================================
# [1/7] 环境预检（ADR-S12-002：一次报全）
# ============================================
CURRENT_STAGE="环境预检"
info "[1/7] 环境预检 ..."

MISSING=()

check_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    MISSING+=("Docker 未安装 — https://docs.docker.com/get-docker/")
    return
  fi
  if ! docker info >/dev/null 2>&1; then
    MISSING+=("Docker daemon 未启动 — 请启动 Docker Desktop / docker 服务")
    return
  fi
  if ! docker compose version >/dev/null 2>&1; then
    MISSING+=("Docker Compose v2 插件不可用 — 请升级 Docker（https://docs.docker.com/compose/install/）")
  fi
}

check_java() {
  if ! command -v java >/dev/null 2>&1; then
    MISSING+=("JDK 未安装（需要 25）— https://adoptium.net/temurin/releases/?version=25")
    return
  fi
  local ver major
  ver=$(java -version 2>&1 | head -1 | grep -oE '"[0-9][0-9._]*"' | tr -d '"' || true)
  if [[ "$ver" == 1.* ]]; then major=$(echo "$ver" | cut -d. -f2); else major=$(echo "$ver" | cut -d. -f1); fi
  if [ "${major:-0}" != "25" ]; then
    MISSING+=("JDK 版本不符：当前 ${ver:-未知}，需要 25 — https://adoptium.net/temurin/releases/?version=25")
  fi
}

check_maven() {
  if ! command -v "$MVN" >/dev/null 2>&1; then
    MISSING+=("Maven 未安装（需要 3.9+）— https://maven.apache.org/download.cgi")
    return
  fi
  local ver major minor
  ver=$("$MVN" -version 2>/dev/null | head -1 | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)
  major=$(echo "${ver:-0.0.0}" | cut -d. -f1); minor=$(echo "${ver:-0.0.0}" | cut -d. -f2)
  if [ "$major" -lt 3 ] || { [ "$major" -eq 3 ] && [ "$minor" -lt 9 ]; }; then
    MISSING+=("Maven 版本不符：当前 ${ver:-未知}，需要 3.9+ — https://maven.apache.org/download.cgi")
  fi
}

check_node() {
  if ! command -v node >/dev/null 2>&1; then
    MISSING+=("Node.js 未安装（需要 18+）— https://nodejs.org/")
    return
  fi
  local major
  major=$(node --version | sed 's/^v//' | cut -d. -f1)
  if [ "$major" -lt 18 ]; then
    MISSING+=("Node.js 版本不符：当前 v${major}.x，需要 18+ — https://nodejs.org/")
  fi
}

check_pnpm() {
  if ! command -v pnpm >/dev/null 2>&1; then
    MISSING+=("pnpm 未安装 — 执行 npm i -g pnpm 或 corepack enable")
  fi
}

check_curl() {
  if ! command -v curl >/dev/null 2>&1; then
    MISSING+=("curl 未安装（健康检查/冒烟需要）— https://curl.se/download.html")
  fi
}

check_docker; check_java; check_maven; check_node; check_pnpm; check_curl

if [ "${#MISSING[@]}" -gt 0 ]; then
  err "环境预检未通过，共 ${#MISSING[@]} 项："
  for m in "${MISSING[@]}"; do echo -e "  ${C_ERR}✗${C_OFF} $m" >&2; done
  exit 1
fi
ok "环境预检通过（Docker / JDK 25 / Maven 3.9+ / Node 18+ / pnpm / curl）"

# ============================================
# [2/7] Doris 配置（ADR-S12-003）
# ============================================
CURRENT_STAGE="Doris 配置"
info "[2/7] Doris 配置 ..."

yaml_get() { grep -E "^\s+$1:" "$DORIS_YAML" | head -1 | sed "s/.*$1:[[:space:]]*//" | tr -d '[:space:]'; }

CUR_HOST=$(yaml_get fe-host); CUR_PORT=$(yaml_get fe-query-port)
CUR_USER=$(yaml_get user);    CUR_PASS=$(yaml_get password)

probe_tcp() { # host port；curl 连 MySQL 协议端口：连通失败=6/7/28，其余视为可达
  local host="$1" port="$2" code
  curl --connect-timeout 3 -s -o /dev/null "http://${host}:${port}" 2>/dev/null && code=0 || code=$?
  if [ "$code" -eq 6 ] || [ "$code" -eq 7 ] || [ "$code" -eq 28 ]; then return 1; fi
  return 0
}

write_doris_yaml() { # host port user password
  cat > "$DORIS_YAML" <<EOF
# ============================================
# shared-doris.yaml
# Apache Doris 连接信息
# 消费方: engineering / worker
# 由 deploy.sh 生成/更新；改配后需重推 Nacos（deploy.sh 会自动处理）
# ============================================
datanest:
  doris:
    fe-host: $1
    fe-query-port: $2
    user: $3
    password: $4
EOF
}

DORIS_CHANGED=0

if [ "$SKIP_DORIS" -eq 1 ]; then
  warn "已按 --skip-doris 跳过 Doris 配置。同步与数仓功能不可用，其余功能正常。"
else
  info "当前 Doris 配置：fe-host=${CUR_HOST:-未配置} fe-query-port=${CUR_PORT:-9030} user=${CUR_USER:-root}"
  if [ -n "$DORIS_HOST" ]; then
    # 非交互模式：参数覆盖
    DORIS_PORT="${DORIS_PORT:-9030}"; DORIS_USER="${DORIS_USER:-root}"; DORIS_PASSWORD="${DORIS_PASSWORD:-password}"
  elif [ "$IS_TTY" -eq 1 ]; then
    read -r -p "是否修改 Doris 配置? [y/N] " ans
    if [[ "$ans" =~ ^[Yy]$ ]]; then
      read -r -p "  FE 主机 [${CUR_HOST:-192.168.1.100}]: " DORIS_HOST;     DORIS_HOST="${DORIS_HOST:-${CUR_HOST:-192.168.1.100}}"
      read -r -p "  查询端口 [${CUR_PORT:-9030}]: " DORIS_PORT;            DORIS_PORT="${DORIS_PORT:-${CUR_PORT:-9030}}"
      read -r -p "  用户     [${CUR_USER:-root}]: " DORIS_USER;            DORIS_USER="${DORIS_USER:-${CUR_USER:-root}}"
      read -r -p "  密码     [${CUR_PASS:-password}]: " DORIS_PASSWORD;    DORIS_PASSWORD="${DORIS_PASSWORD:-${CUR_PASS:-password}}"
    fi
  elif [ -n "$CUR_HOST" ]; then
    # 非交互且无参数：沿用现有配置（探测连通性，不改动文件）
    DORIS_HOST="$CUR_HOST"; DORIS_PORT="${CUR_PORT:-9030}"
    DORIS_USER="${CUR_USER:-root}"; DORIS_PASSWORD="${CUR_PASS:-password}"
  fi

  if [ -n "$DORIS_HOST" ]; then
    info "探测 Doris 连通性 ${DORIS_HOST}:${DORIS_PORT} ..."
    until probe_tcp "$DORIS_HOST" "$DORIS_PORT"; do
      warn "无法连接 Doris ${DORIS_HOST}:${DORIS_PORT}"
      if [ "$IS_TTY" -eq 1 ]; then
        echo "  同步与数仓功能需要可用的外部 Doris（安装指引见 docs/deploy.md）。"
        read -r -p "选择：[r]重新填写 / [s]跳过继续（同步功能不可用）/ [q]退出: " choice
        case "$choice" in
          r|R)
            read -r -p "  FE 主机: " DORIS_HOST
            read -r -p "  查询端口 [9030]: " DORIS_PORT; DORIS_PORT="${DORIS_PORT:-9030}"
            info "重新探测 ${DORIS_HOST}:${DORIS_PORT} ..." ;;
          s|S) DORIS_HOST=""; break ;;
          *)   err "用户中止部署。"; exit 1 ;;
        esac
      else
        warn "非交互模式：Doris 不可达，仍按所给配置继续（同步与数仓功能将不可用）。"
        break
      fi
    done
  fi

  if [ -n "$DORIS_HOST" ]; then
    if [ "$DORIS_HOST" != "$CUR_HOST" ] || [ "$DORIS_PORT" != "$CUR_PORT" ] \
       || [ "$DORIS_USER" != "$CUR_USER" ] || [ "$DORIS_PASSWORD" != "$CUR_PASS" ]; then
      write_doris_yaml "$DORIS_HOST" "$DORIS_PORT" "$DORIS_USER" "$DORIS_PASSWORD"
      DORIS_CHANGED=1
      ok "Doris 配置已更新（${DORIS_HOST}:${DORIS_PORT}）"
    else
      ok "Doris 配置无变化（${DORIS_HOST}:${DORIS_PORT}）"
    fi
  else
    warn "未配置 Doris。同步与数仓功能不可用，后续可重新执行 ./deploy.sh 配置。"
  fi
fi

# ============================================
# [3/7] 后端构建
# ============================================
CURRENT_STAGE="后端构建"
if [ "$SKIP_BUILD" -eq 1 ]; then
  info "[3/7] 跳过后端构建（--skip-build）"
else
  info "[3/7] 后端构建（mvn clean install -DskipTests）..."
  "$MVN" clean install -DskipTests
  ok "后端构建完成"
fi

# ============================================
# [4/7] 前端构建
# ============================================
CURRENT_STAGE="前端构建"
if [ "$SKIP_BUILD" -eq 1 ]; then
  info "[4/7] 跳过前端构建（--skip-build）"
else
  info "[4/7] 前端构建（pnpm install + pnpm build）..."
  (cd data-nest-frontend && pnpm install --frozen-lockfile && pnpm build)
  ok "前端构建完成"
fi

# ============================================
# [5/7] compose 启动
# ============================================
CURRENT_STAGE="容器启动"
PROFILE_ARGS=()
if [ "$WITH_TEST_DEPS" -eq 1 ]; then
  PROFILE_ARGS=(--profile test)
  info "[5/7] 启动全部容器（含 E2E 测试库 profile=test）..."
else
  info "[5/7] 启动全部容器（测试库不启动；E2E 请加 --with-test-deps）..."
fi
# Flink CDC 运行时 jar 不入库（Sprint 12 发布方案③）：缺失时从 GitHub Release 附件拉取
info "检查 Flink 运行时 jar ..."
bash scripts/fetch-flink-libs.sh

# 全栈冷启动时依赖健康检查窗口可能被 JVM 慢启动打爆，compose 会报 dependency failed；
# 此时容器仍在继续启动，重试即可收敛（最多 3 次）
UP_OK=0
for attempt in 1 2 3; do
  if docker compose "${PROFILE_ARGS[@]}" up -d --build; then
    UP_OK=1; break
  fi
  warn "compose up 第 ${attempt} 次未成功（可能是依赖健康检查窗口内的冷启动抖动），30s 后重试..."
  sleep 30
done
if [ "$UP_OK" -ne 1 ]; then
  err "compose up 重试 3 次仍失败。排查：docker compose ps / docker compose logs <服务名>"
  exit 1
fi
ok "容器启动完成"

# Doris 配置变更且 Nacos 已在运行：重推配置并重启消费方（ADR-S12-003）
if [ "$DORIS_CHANGED" -eq 1 ] && docker ps --format '{{.Names}}' | grep -q '^datanest-middleware-nacos$'; then
  info "Doris 配置已变更，重推 Nacos 并重启消费方（engineering / worker）..."
  docker compose up -d --force-recreate middleware-nacos-init
  docker compose restart app-engineering app-worker
fi

# ============================================
# [6/7] 健康等待 + 登录冒烟（ADR-S12-005）
# ============================================
CURRENT_STAGE="健康等待"
info "[6/7] 等待全部容器健康（超时 10 分钟）..."

DEADLINE=$(( $(date +%s) + 600 ))
# 只检查当前 profile 启用的服务（config --services），忽略已停用 profile 的残留容器；
# inspect 失败（容器检查间隙被移除等竞态）不致命，下一轮重试
while true; do
  NOT_READY=()
  for svc in $(docker compose "${PROFILE_ARGS[@]}" config --services); do
    cid=$(docker compose "${PROFILE_ARGS[@]}" ps -aq "$svc" 2>/dev/null | head -1)
    if [ -z "$cid" ]; then NOT_READY+=("$svc(未创建)"); continue; fi
    name=$(docker inspect -f '{{.Name}}' "$cid" 2>/dev/null | sed 's|^/||' || true)
    status=$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || true)
    health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || true)
    if [ -z "$status" ]; then continue; fi  # 容器刚被移除，跳过本轮
    # one-shot 配置初始化容器：退出码 0 即完成
    if [ "$name" = "datanest-middleware-nacos-init" ]; then
      [ "$status" = "exited" ] && [ "$(docker inspect -f '{{.State.ExitCode}}' "$cid" 2>/dev/null)" = "0" ] && continue
      NOT_READY+=("$name($status)"); continue
    fi
    if [ "$status" != "running" ] || { [ "$health" != "none" ] && [ "$health" != "healthy" ]; }; then
      NOT_READY+=("$name($status/$health)")
    fi
  done

  if [ "${#NOT_READY[@]}" -eq 0 ]; then break; fi
  if [ "$(date +%s)" -ge "$DEADLINE" ]; then
    err "等待超时，以下容器未就绪："
    for n in "${NOT_READY[@]}"; do echo -e "  ${C_ERR}✗${C_OFF} $n" >&2; done
    echo "排查：docker compose logs <服务名>（如 docker compose logs app-gateway）" >&2
    exit 1
  fi
  echo "  等待中（${#NOT_READY[@]} 个未就绪）: ${NOT_READY[*]}"
  sleep 5
done
ok "全部容器就绪"

info "登录冒烟（admin / admin123）..."
SMOKE_OK=0
for i in $(seq 1 12); do
  if curl -sf -X POST http://localhost:8080/api/system/auth/login \
      -H 'Content-Type: application/json' \
      -d '{"username":"admin","password":"admin123"}' 2>/dev/null | grep -q 'token'; then
    SMOKE_OK=1; break
  fi
  sleep 5
done
if [ "$SMOKE_OK" -ne 1 ]; then
  err "登录冒烟失败：网关或 system 服务异常。排查：docker compose logs app-gateway app-system"
  exit 1
fi
ok "登录冒烟通过"

# ============================================
# [7/7] 打印访问信息
# ============================================
CURRENT_STAGE="完成"
echo
echo -e "${C_OK}============================================${C_OFF}"
echo -e "${C_OK}  DataNest 部署完成${C_OFF}"
echo -e "${C_OK}============================================${C_OFF}"
cat <<'EOF'
  平台入口（前端）   http://localhost:3000        admin / admin123
  网关 API           http://localhost:8080
  接口文档           http://localhost:8080/swagger-ui.html
  PowerJob 控制台    http://localhost:7700        App 密码 powerjob123
  Nacos 控制台       http://localhost:8081        nacos / nacos
  MinIO 控制台       http://localhost:9001        datanest / datanest123
  Flink Web UI       http://localhost:18081
  MailHog（邮件）    http://localhost:8025

  注意：以上账号密码均为本地开发默认值，生产环境必须修改。
  Doris 为外部依赖；同步与数仓功能需要可用的 Doris（配置见 docs/deploy.md）。
EOF
trap - ERR
