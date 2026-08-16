FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY payment-service-java/build.gradle payment-service-java/
COPY payment-service-java/src payment-service-java/src
COPY mock-pg-server/build.gralde mock-pg-server/

RUN chmod +x gradlew && ./gradlew :payment-service-java:bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine

RUN adduser -D -s /bin/false appuser

WORKDIR /app

COPY --from=build /workspace/payment-service-java/build/libs/*.jar app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
