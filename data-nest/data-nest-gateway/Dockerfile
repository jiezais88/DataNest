FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache netcat-openbsd

WORKDIR /app

COPY target/data-nest-gateway-1.0.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
