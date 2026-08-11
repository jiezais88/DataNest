#!/bin/sh
set -e

NACOS_HOST="${NACOS_HOST:-nacos}"
NACOS_PORT="${NACOS_PORT:-8848}"
NACOS_URL="http://${NACOS_HOST}:${NACOS_PORT}/nacos"
CONFIG_DIR="${CONFIG_DIR:-/shared-configs}"
GROUP="${NACOS_GROUP:-shared-configs}"
NACOS_USERNAME="${NACOS_USERNAME:-}"
NACOS_PASSWORD="${NACOS_PASSWORD:-}"

ACCESS_TOKEN=""

login_nacos() {
  if [ -z "$NACOS_USERNAME" ] || [ -z "$NACOS_PASSWORD" ]; then
    echo "NACOS_USERNAME or NACOS_PASSWORD not set, skipping login."
    return
  fi

  echo "Logging in to Nacos as ${NACOS_USERNAME} ..."
  login_response=$(curl -s -X POST "${NACOS_URL}/v1/auth/users/login" \
    -d "username=${NACOS_USERNAME}" \
    -d "password=${NACOS_PASSWORD}")

  token=$(echo "$login_response" | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
  if [ -z "$token" ]; then
    # 登录失败直接失败退出（由容器 restart: on-failure 重试整个脚本），
    # 不得降级为无 token 发布（401 静默失败会造成「假成功」，下游服务拿到残缺配置启动即崩）
    echo "ERROR: Failed to obtain Nacos access token. Response: ${login_response}"
    exit 1
  fi

  ACCESS_TOKEN="$token"
  echo "Nacos login succeeded."
}

echo "Waiting for Nacos at ${NACOS_URL} ..."
# 登录探针就绪检查：能签发 token = HTTP + 存储 + 鉴权子系统均已就绪
# （Nacos 3.x 无 readiness 端点，metrics 报 UP 时 gRPC/鉴权可能尚未就绪）
until curl -s -X POST "${NACOS_URL}/v1/auth/users/login" \
    -d "username=${NACOS_USERNAME}" -d "password=${NACOS_PASSWORD}" | grep -q 'accessToken'; do
  echo "Nacos not ready yet, retrying in 3s ..."
  sleep 3
done
echo "Nacos is ready."

login_nacos

echo "Importing shared configs from ${CONFIG_DIR} ..."
for file in "${CONFIG_DIR}"/*.yaml; do
  [ -e "$file" ] || continue
  dataId=$(basename "$file")
  echo "Publishing: ${dataId}"

  if [ -n "$ACCESS_TOKEN" ]; then
    response=$(curl -s -X POST "${NACOS_URL}/v1/cs/configs" \
      -d "dataId=${dataId}" \
      -d "group=${GROUP}" \
      -d "type=yaml" \
      -d "accessToken=${ACCESS_TOKEN}" \
      --data-urlencode "content@${file}")
  else
    response=$(curl -s -X POST "${NACOS_URL}/v1/cs/configs" \
      -d "dataId=${dataId}" \
      -d "group=${GROUP}" \
      -d "type=yaml" \
      --data-urlencode "content@${file}")
  fi
  # 发布失败（响应非 true）直接失败退出，交给 restart: on-failure 重试，避免「假成功」
  if [ "$response" != "true" ]; then
    echo "ERROR: publish ${dataId} failed. Response: ${response}"
    exit 1
  fi
  echo "Done: ${dataId}"
done

echo "Nacos config import completed."
