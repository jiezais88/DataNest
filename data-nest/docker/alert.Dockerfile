# ============================================
# DataNest Alert Service Dockerfile
# 分层构建：依赖层可复用 Docker 缓存，改代码只重建应用层
# 依赖根 pom 已启用 spring-boot-maven-plugin layers
# ============================================

# Stage 1: 解出分层 jar
FROM eclipse-temurin:21-jre-alpine AS builder
WORKDIR /build
COPY data-nest-alert-service/target/data-nest-alert-service-1.0.0-SNAPSHOT.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract

# Stage 2: 运行镜像
FROM eclipse-temurin:21-jre-alpine

# netcat 供健康检查 / 等待依赖使用
RUN apk add --no-cache netcat-openbsd

WORKDIR /app

# 分层复制：依赖层(不变) + spring-boot-loader + 应用层(常变)
COPY --from=builder /build/dependencies/ ./
COPY --from=builder /build/spring-boot-loader/ ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/ ./

EXPOSE 8088

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
