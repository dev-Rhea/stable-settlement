FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY payment-service-java/build.gradle payment-service-java/
COPY mockpg/build.gradle mockpg/
COPY reconciliation-batch/build.gradle reconciliation-batch/
COPY reconciliation-batch/src reconciliation-batch/src

RUN chmod +x gradlew && ./gradlew :reconciliation-batch:bootJar -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine

RUN adduser -D -s /bin/false appuser

WORKDIR /app

COPY --from=build /workspace/reconciliation-batch/build/libs/*.jar app.jar

USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]
