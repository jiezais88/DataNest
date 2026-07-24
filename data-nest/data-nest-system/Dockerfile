FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache netcat-openbsd

WORKDIR /app

COPY target/data-nest-system-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8087

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
