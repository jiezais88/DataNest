# ============================================
# DataNest Worker Service Dockerfile
# 多阶段：解分层 jar + 从 wgzhao/addax 拷贝 Addax 二进制
# 依赖层可复用 Docker 缓存，改代码只重建应用层
#
# 双 JRE（2026-08-11 JDK 25 升级）：
#   应用跑 JRE 25（/opt/java/openjdk）；Addax 子进程固定 JRE 21（/opt/jre21）——
#   addax.sh 带 `-Djava.security.manager=allow`，而 SecurityManager 在 JDK 24 已彻底移除，
#   Addax 在 25 上无法启动。addax.sh 用 PATH 里的 java（无 JAVA_HOME 逻辑），
#   且本镜像 PATH 把 /opt/addax/bin 放最前，故放一个 java shim 指向 JRE 21；
#   应用入口用绝对路径绕过 shim，不受影响。
# ============================================

# Stage 1: Addax 二进制
FROM quay.io/wgzhao/addax:6.0.11 AS addax

# Stage 2: 解出分层 jar
FROM eclipse-temurin:25-jre-alpine AS builder
WORKDIR /build
COPY data-nest-services/data-nest-worker/target/data-nest-worker-1.0.0-SNAPSHOT.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Stage 3: JRE 21（仅供 Addax 子进程使用）
FROM eclipse-temurin:21-jre-alpine AS jre21

# Stage 4: 构建应用运行镜像（JRE 25）
FROM eclipse-temurin:25-jre-alpine

RUN apk add --no-cache bash netcat-openbsd python3 py3-pip && \
    pip3 install --no-cache-dir pandas pymysql psycopg2-binary oracledb --break-system-packages

# 拷贝 Addax + JRE 21（Addax 专用）
COPY --from=addax /opt/addax /opt/addax
COPY --from=jre21 /opt/java/openjdk /opt/jre21
ENV ADDAX_HOME=/opt/addax
ENV PATH="${ADDAX_HOME}/bin:${PATH}"

# Addax java shim：PATH 中 /opt/addax/bin 最前，addax.sh 的 `java` 解析到 JRE 21
RUN printf '#!/bin/sh\nexec /opt/jre21/bin/java "$@"\n' > /opt/addax/bin/java && chmod +x /opt/addax/bin/java

WORKDIR /app

# 分层复制：依赖层(不变) + spring-boot-loader + 应用层(常变)
COPY --from=builder /build/dependencies/ ./
COPY --from=builder /build/spring-boot-loader/ ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/ ./

EXPOSE 8085 9997

# 绝对路径绕过 /opt/addax/bin 的 shim，应用跑 JRE 25
ENTRYPOINT ["/opt/java/openjdk/bin/java", "org.springframework.boot.loader.launch.JarLauncher"]
