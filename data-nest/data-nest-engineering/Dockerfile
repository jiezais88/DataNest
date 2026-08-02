# ============================================
# DataNest Engineering Service Dockerfile
# ============================================

FROM eclipse-temurin:21-jre-alpine

# Python 3 + pip + pandas/pymysql，供 Sprint 4 Python 节点执行
RUN apk add --no-cache python3 py3-pip && \
    python3 --version && \
    pip3 --version && \
    pip3 install --no-cache-dir pandas pymysql --break-system-packages

WORKDIR /app

COPY target/data-nest-engineering-1.0.0-SNAPSHOT.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 8082

ENTRYPOINT ["/docker-entrypoint.sh"]
