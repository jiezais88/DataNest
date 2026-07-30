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
    echo "WARNING: Failed to obtain Nacos access token. Response: ${login_response}"
    return
  fi

  ACCESS_TOKEN="$token"
  echo "Nacos login succeeded."
}

echo "Waiting for Nacos at ${NACOS_URL} ..."
until curl -s "${NACOS_URL}/v1/ns/operator/metrics" | grep -q 'UP'; do
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
    curl -s -X POST "${NACOS_URL}/v1/cs/configs" \
      -d "dataId=${dataId}" \
      -d "group=${GROUP}" \
      -d "type=yaml" \
      -d "accessToken=${ACCESS_TOKEN}" \
      --data-urlencode "content@${file}" \
      -o /tmp/init-nacos-response.log
  else
    curl -s -X POST "${NACOS_URL}/v1/cs/configs" \
      -d "dataId=${dataId}" \
      -d "group=${GROUP}" \
      -d "type=yaml" \
      --data-urlencode "content@${file}" \
      -o /tmp/init-nacos-response.log
  fi
  echo "Done: ${dataId}"
done

echo "Nacos config import completed."
