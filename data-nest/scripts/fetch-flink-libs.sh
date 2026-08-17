#!/usr/bin/env bash
# ============================================
# 拉取 Flink CDC 运行时 jar（Sprint 12 发布方案③）
#
# 背景：10 个运行时 jar（约 182MB）不入 git 仓库，
# 作为 GitHub Release v1.0.0 附件分发，本脚本按需下载 + sha256 校验。
# 已存在的文件直接跳过（离线可重复执行）。
#
# 用法: bash scripts/fetch-flink-libs.sh
# ============================================
set -euo pipefail

cd "$(dirname "$0")/.."
LIB_DIR="docker/flink/lib"
BASE_URL="https://github.com/jiezais88/DataNest/releases/download/v1.0.0"
mkdir -p "$LIB_DIR"

# sha256 校验工具（Linux/Git Bash: sha256sum；macOS: shasum）
sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# 文件名 + sha256（2026-08-17 入库前实测）
JARS="
flink-cdc-common-3.6.0-2.2.jar 9a60c77e4b614efa596e944b9bcd29029e08377ed622c84db8b831571adfa75e
flink-cdc-dist-3.6.0-2.2.jar a36847b388d2635e9437fc64d7eea43620275c70b15e82cc3ab0a902ff26e5fd
flink-cdc-flink2-compat-3.6.0-2.2.jar 5e4c9f5dbf94c7ea437e9c9fd675042c18040add9bb5b3987a4c77d5ed2f4e94
flink-cdc-pipeline-connector-iceberg-3.6.0-2.2.jar 65cbb8c7643ccc7648ced4d9a85bde3edaba2cf65c6b9f75f839c9a25e9620cc
flink-cdc-pipeline-connector-kafka-3.6.0-2.2.jar a32859c1fdd098e4da333cdb739c06ba20fe99204b70196e55f6f8e8cba922da
flink-cdc-pipeline-connector-mysql-3.6.0-2.2.jar 6d559f2b05571368b4049c52b37d3509cd34f87e7e0bdb225ed2d4e837241e6c
flink-cdc-pipeline-connector-postgres-3.6.0-2.2.jar cc69fae6538a6ed4797968cf7dada12848726de59dc6b5b672aa638ac291c65a
flink-s3-fs-hadoop-2.2.1.jar f1a7f8647de3f7ac2c4677f9ba221ca3ed32e9541f61967da445c36aadcaf927
flink-shaded-hadoop-2-uber-2.8.3-10.0.jar 492b2a559f2a1dad3808b51d9a26a575dbb1202004c9f85f5059c520e0632127
mysql-connector-j-8.0.33.jar e2a3b2fc726a1ac64e998585db86b30fa8bf3f706195b78bb77c5f99bf877bd9
"

FAIL=0
echo "$JARS" | while read -r jar expect; do
  [ -z "$jar" ] && continue
  target="$LIB_DIR/$jar"
  if [ -f "$target" ]; then
    echo "[跳过] $jar（已存在）"
    continue
  fi
  echo "[下载] $jar ..."
  if ! curl -fL --retry 3 --connect-timeout 10 -o "$target" "${BASE_URL}/${jar}"; then
    echo "[失败] $jar 下载失败：${BASE_URL}/${jar}" >&2
    rm -f "$target"
    exit 1
  fi
  actual=$(sha256 "$target")
  if [ "$actual" != "$expect" ]; then
    echo "[失败] $jar sha256 校验不符（期望 $expect，实际 $actual）" >&2
    rm -f "$target"
    exit 1
  fi
  echo "[完成] $jar（sha256 校验通过）"
done

count=$(ls "$LIB_DIR"/*.jar 2>/dev/null | wc -l)
if [ "$count" -ne 10 ]; then
  echo "错误：$LIB_DIR 下应有 10 个 jar，实际 $count 个" >&2
  exit 1
fi
echo "Flink 运行时 jar 齐备（10 个，$LIB_DIR/）"
