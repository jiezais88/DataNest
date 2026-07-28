#!/bin/sh
set -e

# 等待 Nacos 可用（可选）
if [ -n "${NACOS_HOST}" ] && [ -n "${NACOS_PORT}" ]; then
    echo "Waiting for Nacos at ${NACOS_HOST}:${NACOS_PORT}..."
    while ! nc -z "${NACOS_HOST}" "${NACOS_PORT}"; do
        sleep 1
    done
    echo "Nacos is available."
fi

# 验证 Addax 可用
if [ ! -d "${ADDAX_HOME}" ]; then
    echo "ADDAX_HOME directory ${ADDAX_HOME} does not exist"
    exit 1
fi

echo "ADDAX_HOME=${ADDAX_HOME}"
echo "Starting DataNest Engineering Service..."

exec java -jar /app/app.jar "$@"
