# ============================================
# DataNest Engineering Service Dockerfile
# 多阶段构建：从 wgzhao/addax 拷贝 Addax 二进制到 JDK 21 镜像
# ============================================

# Stage 1: Addax 二进制
FROM wgzhao/addax:6.0.11 AS addax

# Stage 2: 构建应用运行镜像
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache bash netcat-openbsd

# 拷贝 Addax
COPY --from=addax /opt/addax /opt/addax
ENV ADDAX_HOME=/opt/addax
ENV PATH="${ADDAX_HOME}/bin:${PATH}"

WORKDIR /app

COPY target/data-nest-engineering-1.0.0-SNAPSHOT.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 8082

ENTRYPOINT ["/docker-entrypoint.sh"]
