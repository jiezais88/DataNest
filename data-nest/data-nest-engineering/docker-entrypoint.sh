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

echo "Starting DataNest Engineering Service..."

exec java org.springframework.boot.loader.launch.JarLauncher "$@"
