# ============================================
# DataNest Worker Service Dockerfile
# 多阶段：解分层 jar + 从 wgzhao/addax 拷贝 Addax 二进制
# 依赖层可复用 Docker 缓存，改代码只重建应用层
# ============================================

# Stage 1: Addax 二进制
FROM quay.io/wgzhao/addax:6.0.11 AS addax

# Stage 2: 解出分层 jar
FROM eclipse-temurin:21-jre-alpine AS builder
WORKDIR /build
COPY data-nest-worker/target/data-nest-worker-1.0.0-SNAPSHOT.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Stage 3: 构建应用运行镜像
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache bash netcat-openbsd python3 py3-pip && \
    pip3 install --no-cache-dir pandas pymysql --break-system-packages

# 拷贝 Addax
COPY --from=addax /opt/addax /opt/addax
ENV ADDAX_HOME=/opt/addax
ENV PATH="${ADDAX_HOME}/bin:${PATH}"

WORKDIR /app

# 分层复制：依赖层(不变) + spring-boot-loader + 应用层(常变)
COPY --from=builder /build/dependencies/ ./
COPY --from=builder /build/spring-boot-loader/ ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/ ./

EXPOSE 8085 9997

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
