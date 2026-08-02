# ============================================
# DataNest Worker Service Dockerfile
# 多阶段构建：从 wgzhao/addax 拷贝 Addax 二进制到 JDK 21 镜像
# ============================================

# Stage 1: Addax 二进制
FROM quay.io/wgzhao/addax:6.0.11 AS addax

# Stage 2: 构建应用运行镜像
FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache bash netcat-openbsd python3 py3-pip && \
    pip3 install --no-cache-dir pandas pymysql --break-system-packages

# 拷贝 Addax
COPY --from=addax /opt/addax /opt/addax
ENV ADDAX_HOME=/opt/addax
ENV PATH="${ADDAX_HOME}/bin:${PATH}"

WORKDIR /app

COPY target/data-nest-worker-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8085 9997

ENTRYPOINT ["java", "-jar", "app.jar"]
