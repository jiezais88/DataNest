# ============================================
# DataNest Engineering Service Dockerfile
# ============================================

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache netcat-openbsd

WORKDIR /app

COPY target/data-nest-engineering-1.0.0-SNAPSHOT.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh

EXPOSE 8082

ENTRYPOINT ["/docker-entrypoint.sh"]
