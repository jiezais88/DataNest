#!/bin/sh
set -e

NACOS_HOST="${NACOS_HOST:-nacos}"
NACOS_PORT="${NACOS_PORT:-8848}"
NACOS_URL="http://${NACOS_HOST}:${NACOS_PORT}/nacos"
CONFIG_DIR="${CONFIG_DIR:-/shared-configs}"
GROUP="${NACOS_GROUP:-shared-configs}"

echo "Waiting for Nacos at ${NACOS_URL} ..."
until curl -s "${NACOS_URL}/v1/ns/operator/metrics" | grep -q 'UP'; do
  echo "Nacos not ready yet, retrying in 3s ..."
  sleep 3
done
echo "Nacos is ready."

echo "Importing shared configs from ${CONFIG_DIR} ..."
for file in "${CONFIG_DIR}"/*.yaml; do
  [ -e "$file" ] || continue
  dataId=$(basename "$file")
  echo "Publishing: ${dataId}"
  curl -s -X POST "${NACOS_URL}/v1/cs/configs" \
    -d "dataId=${dataId}" \
    -d "group=${GROUP}" \
    -d "type=yaml" \
    --data-urlencode "content@${file}" \
    -o /tmp/init-nacos-response.log
  echo "Done: ${dataId}"
done

echo "Nacos config import completed."
