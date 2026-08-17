# ============================================
# DataNest Engineering Service Dockerfile
# 分层构建：依赖层可复用 Docker 缓存，改代码只重建应用层
# 依赖根 pom 已启用 spring-boot-maven-plugin layers
# ============================================

# Stage 1: 解出分层 jar
FROM eclipse-temurin:25-jre-alpine AS builder
WORKDIR /build
COPY data-nest-services/data-nest-engineering/target/data-nest-engineering-1.0.0-SNAPSHOT.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Stage 2: 运行镜像
FROM eclipse-temurin:25-jre-alpine

# netcat 供等待依赖使用
RUN apk add --no-cache netcat-openbsd

# Python 3 + pip + pandas/pymysql，供 Sprint 4 Python 节点执行
RUN apk add --no-cache python3 py3-pip && \
    python3 --version && \
    pip3 --version && \
    pip3 install --no-cache-dir pandas pymysql --break-system-packages

WORKDIR /app

# 分层复制：依赖层(不变) + spring-boot-loader + 应用层(常变)
COPY --from=builder /build/dependencies/ ./
COPY --from=builder /build/spring-boot-loader/ ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/ ./

# 统一启动脚本：JVM 启动前等待中间件 TCP 就绪（Docker daemon 重启无依赖编排兜底）
COPY docker/wait-and-start.sh /wait-and-start.sh
RUN chmod +x /wait-and-start.sh

EXPOSE 8082

ENTRYPOINT ["/wait-and-start.sh"]
